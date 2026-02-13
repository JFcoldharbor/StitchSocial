/*
 * UserService.kt - FIXED: SUBCOLLECTION STRUCTURE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * ✅ CRITICAL FIX: Using subcollection structure to match Swift
 * ✅ Structure: users/{userID}/following/{followeeID}
 * ✅ Structure: users/{userID}/followers/{followerID}
 * ✅ All 8 methods updated with correct subcollection paths
 */

package com.stitchsocial.club.services

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.min
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.stitchsocial.club.foundation.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.*

/**
 * Enhanced UserService with complete profile editing, social features, and search capabilities
 * Uses subcollection structure: users/{userID}/following/{followeeID}
 */
class UserService(private val context: Context) {

    // Use "stitchfin" database for consistency
    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val storage = FirebaseStorage.getInstance()

    // Cache integration for performance
    private val cachingService = SimplifiedCachingService(context)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastError = MutableStateFlow<StitchError?>(null)
    val lastError: StateFlow<StitchError?> = _lastError.asStateFlow()

    // Search cache for performance
    private val searchCache = mutableMapOf<String, Pair<List<BasicUserInfo>, Long>>()
    private val cacheExpiration = 300000L // 5 minutes
    private val maxCacheEntries = 50

    // ===== USER CREATION WITH AUTO-FOLLOW SYSTEM =====

    /**
     * Create new user with complete profile setup, special user detection, and auto-follow
     */
    suspend fun createUser(
        id: String,
        email: String,
        username: String? = null,
        displayName: String? = null,
        profileImageURL: String? = null
    ): BasicUserInfo? {
        return try {
            _isLoading.value = true
            println("USER SERVICE: Creating user with email: $email")

            // Generate username if not provided
            val finalUsername = username ?: generateUsername(email, id)
            val finalDisplayName = displayName ?: finalUsername

            // Detect special user and use their configured tier
            val specialUserEntry = SpecialUsersConfig.detectSpecialUser(email)

            // Use actual tier from SpecialUserEntry
            val initialTier = if (specialUserEntry != null) {
                UserTier.fromRawValue(specialUserEntry.tierRawValue) ?: UserTier.ROOKIE
            } else {
                UserTier.ROOKIE
            }

            val initialClout = specialUserEntry?.startingClout ?: 0
            val isSpecialUser = specialUserEntry != null
            val initialBio = specialUserEntry?.customBio ?: ""

            println("USER SERVICE: Creating user with tier ${initialTier.displayName}, clout: $initialClout")

            // Create user document
            val userData = hashMapOf<String, Any>(
                "id" to id,
                "email" to email,
                "username" to finalUsername,
                "displayName" to finalDisplayName,
                "bio" to initialBio,
                "tier" to initialTier.rawValue,
                "clout" to initialClout,
                "followerCount" to 0,
                "followingCount" to 0,
                "isVerified" to isSpecialUser,
                "isPrivate" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )

            if (profileImageURL != null) {
                userData["profileImageURL"] = profileImageURL
            }

            db.collection("users").document(id).set(userData).await()

            val user = BasicUserInfo(
                id = id,
                username = finalUsername,
                displayName = finalDisplayName,
                email = email,
                bio = initialBio,
                tier = initialTier,
                clout = initialClout,
                followerCount = 0,
                followingCount = 0,
                isVerified = isSpecialUser,
                isPrivate = false,
                profileImageURL = profileImageURL,
                createdAt = Date()
            )

            // Auto-follow James Fortune (founder) for all new users
            performAutoFollow(id, email)

            println("USER SERVICE: ✅ Created user ${finalUsername} with tier ${initialTier.displayName}")
            user

        } catch (e: Exception) {
            println("USER SERVICE: ❌ Error creating user: ${e.message}")
            handleError("Failed to create user: ${e.message}")
            null
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Auto-follow James Fortune (founder) for new users
     */
    private suspend fun performAutoFollow(newUserID: String, email: String) {
        try {
            // Get James Fortune's user ID
            val founderEmail = "james@stitchsocial.me"
            val founderDoc = db.collection("users")
                .whereEqualTo("email", founderEmail)
                .limit(1)
                .get()
                .await()

            if (founderDoc.documents.isNotEmpty()) {
                val founderID = founderDoc.documents.first().id

                // Don't auto-follow yourself
                if (newUserID != founderID) {
                    println("USER SERVICE: Auto-following founder for new user")
                    followUser(newUserID, founderID)
                }
            }
        } catch (e: Exception) {
            println("USER SERVICE: ⚠️ Auto-follow failed (non-critical): ${e.message}")
        }
    }

    /**
     * Generate username from email and ID
     */
    private fun generateUsername(email: String, id: String): String {
        val emailPrefix = email.substringBefore("@").lowercase()
        val cleanPrefix = emailPrefix.replace(Regex("[^a-z0-9]"), "")
        val shortID = id.takeLast(4)
        return "${cleanPrefix}_${shortID}"
    }

    // ===== NEW METHODS FOR ENGAGEMENT SYSTEM =====

    /**
     * Get basic user info - Alias for getUserProfile
     * Used by EngagementCoordinator for consistency
     */
    suspend fun getBasicUserInfo(userID: String): BasicUserInfo? {
        return getUserProfile(userID)
    }

    // ===== NEW METHODS FOR PROFILE SYSTEM =====

    /**
     * Alias for getFollowing - matches ProfileViewModel expectations
     */
    suspend fun getFollowingList(userID: String, limit: Int = 50): List<BasicUserInfo> {
        return getFollowing(userID, limit)
    }

    /**
     * Alias for getFollowers - matches ProfileViewModel expectations
     */
    suspend fun getFollowersList(userID: String, limit: Int = 50): List<BasicUserInfo> {
        return getFollowers(userID, limit)
    }

    /**
     * ✅ FIXED: Using subcollection structure users/{userID}/following
     * Get list of user IDs that this user is following
     * Used by HybridHomeFeedService for feed filtering
     */
    suspend fun getFollowingIDs(userID: String): List<String> {
        return try {
            println("USER SERVICE: Loading following IDs for user $userID")

            val followDocs = db.collection("users")
                .document(userID)
                .collection("following")
                .get()
                .await()

            val followingIDs = followDocs.documents.map { it.id }

            println("USER SERVICE: ✅ Found ${followingIDs.size} following IDs")
            followingIDs

        } catch (e: Exception) {
            println("USER SERVICE: ❌ Error loading following IDs: ${e.message}")
            emptyList()
        }
    }

    /**
     * Update user profile - alias for updateUserProfile
     * Matches ProfileViewModel expected method signature
     */
    suspend fun updateProfile(
        userID: String,
        displayName: String? = null,
        bio: String? = null,
        username: String? = null,
        isPrivate: Boolean? = null
    ): BasicUserInfo? {
        return try {
            // Call existing updateUserProfile method
            val success = updateUserProfile(
                userID = userID,
                displayName = displayName,
                username = username,
                bio = bio,
                profileImageUri = null // No image for this variant
            )

            if (success) {
                // Return updated user profile
                getUserProfile(userID)
            } else {
                null
            }
        } catch (e: Exception) {
            println("USER SERVICE: ❌ Error in updateProfile: ${e.message}")
            null
        }
    }

    // ===== ENHANCED USER PROFILE LOADING WITH DEBUG =====

    /**
     * Get user profile with enhanced debugging and cache integration
     */
    suspend fun getUserProfile(userID: String): BasicUserInfo? {
        return try {
            println("USER SERVICE: Loading profile for user: $userID")

            val userDoc = db.collection("users").document(userID).get().await()

            if (!userDoc.exists()) {
                println("USER SERVICE: ❌ User document not found: $userID")
                return null
            }

            val user = BasicUserInfo.fromFirebaseDocument(userDoc)
            if (user != null) {
                println("USER SERVICE: ✅ Successfully loaded user: ${user.username}")
            } else {
                println("USER SERVICE: ❌ Failed to parse user document")
            }

            user

        } catch (e: Exception) {
            println("USER SERVICE: ❌ Error loading user profile: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Get extended profile data for editing
     */
    suspend fun getExtendedProfile(id: String): BasicUserInfo? {
        return getUserProfile(id)
    }

    /**
     * Batch load multiple user profiles efficiently
     * ✅ FIXED: Use FieldPath.documentId() instead of "id" field
     */
    suspend fun batchLoadUsers(userIDs: List<String>): Map<String, BasicUserInfo> {
        if (userIDs.isEmpty()) return emptyMap()

        return try {
            println("USER SERVICE: Batch loading ${userIDs.size} users")

            val users = mutableMapOf<String, BasicUserInfo>()

            // Firebase has a limit of 10 documents per "in" query
            userIDs.chunked(10).forEach { chunk ->
                val docs = db.collection("users")
                    .whereIn(com.google.firebase.firestore.FieldPath.documentId(), chunk)
                    .get()
                    .await()

                docs.documents.forEach { doc ->
                    BasicUserInfo.fromFirebaseDocument(doc)?.let { user ->
                        users[user.id] = user
                    }
                }
            }

            println("USER SERVICE: Loaded ${users.size}/${userIDs.size} users")
            users

        } catch (e: Exception) {
            println("USER SERVICE: Error in batch load: ${e.message}")
            emptyMap()
        }
    }

    // ===== PROFILE EDITING FUNCTIONALITY =====

    /**
     * Update user profile with validation
     */
    suspend fun updateUserProfile(
        userID: String,
        displayName: String? = null,
        username: String? = null,
        bio: String? = null,
        profileImageUri: Uri? = null
    ): Boolean {
        return try {
            _isLoading.value = true
            println("USER SERVICE: Updating profile for user: $userID")

            val updates = mutableMapOf<String, Any>()

            // Validate and add display name
            displayName?.let {
                if (it.isNotBlank() && it.length <= 50) {
                    updates["displayName"] = it
                }
            }

            // Validate and add username (check uniqueness)
            username?.let {
                if (isValidUsername(it)) {
                    val isUnique = checkUsernameAvailability(it, userID)
                    if (isUnique) {
                        updates["username"] = it
                    } else {
                        handleError("Username already taken")
                        return false
                    }
                } else {
                    handleError("Invalid username format")
                    return false
                }
            }

            // Validate and add bio
            bio?.let {
                if (it.length <= 500) {
                    updates["bio"] = it
                }
            }

            // Upload profile image if provided
            profileImageUri?.let { uri ->
                val imageUrl = uploadProfileImage(userID, uri)
                if (imageUrl != null) {
                    updates["profileImageURL"] = imageUrl
                } else {
                    handleError("Failed to upload profile image")
                }
            }

            // Update Firestore
            if (updates.isNotEmpty()) {
                updates["updatedAt"] = FieldValue.serverTimestamp()
                db.collection("users").document(userID).update(updates).await()
                println("USER SERVICE: ✅ Profile updated successfully")
                invalidateUserCache(userID)
                true
            } else {
                println("USER SERVICE: No valid updates to apply")
                false
            }

        } catch (e: Exception) {
            handleError("Failed to update profile: ${e.message}")
            false
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Update profile with image - used by ProfileView
     */
    suspend fun updateProfileWithImage(
        userID: String,
        displayName: String,
        bio: String,
        imageUri: Uri?
    ) {
        updateUserProfile(
            userID = userID,
            displayName = displayName,
            bio = bio,
            profileImageUri = imageUri
        )
    }

    /**
     * Check if username is available (excluding current user)
     * NOW PUBLIC for ProfileViewModel access
     */
    suspend fun checkUsernameAvailability(username: String, excludeUserID: String): Boolean {
        return try {
            val existingUser = db.collection("users")
                .whereEqualTo("username", username)
                .limit(1)
                .get()
                .await()

            val isAvailable = existingUser.documents.isEmpty() ||
                    existingUser.documents.first().id == excludeUserID

            println("USER SERVICE: Username '$username' available: $isAvailable")
            isAvailable

        } catch (e: Exception) {
            println("USER SERVICE: Error checking username: ${e.message}")
            false
        }
    }

    /**
     * Upload profile image with compression
     */
    private suspend fun uploadProfileImage(userID: String, imageUri: Uri): String? {
        return try {
            val imageData = compressImage(imageUri) ?: return null

            val imageRef = storage.reference.child("profile_images/$userID.jpg")
            val uploadTask = imageRef.putBytes(imageData).await()
            val downloadUrl = imageRef.downloadUrl.await()

            println("USER SERVICE: Profile image uploaded for user $userID")
            downloadUrl.toString()

        } catch (e: Exception) {
            println("USER SERVICE: Error uploading profile image: ${e.message}")
            null
        }
    }

    /**
     * Compress image to reduce storage costs and improve performance
     */
    private suspend fun compressImage(uri: Uri): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                // Resize if too large (max 1024x1024)
                val maxDimension = 1024
                val scale = min(
                    maxDimension.toFloat() / bitmap.width,
                    maxDimension.toFloat() / bitmap.height
                )

                val resizedBitmap = if (scale < 1) {
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt(),
                        (bitmap.height * scale).toInt(),
                        true
                    )
                } else {
                    bitmap
                }

                // Compress to JPEG
                val outputStream = ByteArrayOutputStream()
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                val imageData = outputStream.toByteArray()

                println("USER SERVICE: Image compressed to ${imageData.size / 1024}KB")
                imageData

            } catch (e: Exception) {
                println("USER SERVICE: Error compressing image: ${e.message}")
                null
            }
        }
    }

    // ===== SOCIAL FOLLOWING FUNCTIONALITY =====

    /**
     * ✅ FIXED: Using subcollection structure users/{followerID}/following/{followeeID}
     * Follow a user with atomic updates
     */
    suspend fun followUser(followerID: String, followeeID: String): Boolean {
        return try {
            println("USER SERVICE: User $followerID following $followeeID")

            // Create follow relationship in follower's subcollection
            val followData = hashMapOf(
                "followeeID" to followeeID,
                "createdAt" to FieldValue.serverTimestamp()
            )

            db.collection("users")
                .document(followerID)
                .collection("following")
                .document(followeeID)
                .set(followData)
                .await()

            // Create reverse relationship in followee's subcollection
            val followerData = hashMapOf(
                "followerID" to followerID,
                "createdAt" to FieldValue.serverTimestamp()
            )

            db.collection("users")
                .document(followeeID)
                .collection("followers")
                .document(followerID)
                .set(followerData)
                .await()

            // Update follower count atomically
            db.collection("users").document(followeeID)
                .update("followerCount", FieldValue.increment(1))
                .await()

            // Update following count atomically
            db.collection("users").document(followerID)
                .update("followingCount", FieldValue.increment(1))
                .await()

            updateUserCaches(followerID, followeeID)
            println("USER SERVICE: ✅ Follow relationship created")
            true

        } catch (e: Exception) {
            handleError("Failed to follow user: ${e.message}")
            false
        }
    }

    /**
     * ✅ FIXED: Using subcollection structure users/{followerID}/following/{followeeID}
     * Unfollow a user with atomic updates and founder protection
     */
    suspend fun unfollowUser(followerID: String, followeeID: String): Boolean {
        return try {
            // FOUNDER PROTECTION: Prevent unfollowing James Fortune
            val followeeDoc = db.collection("users").document(followeeID).get().await()
            val followeeEmail = followeeDoc.getString("email")

            if (followeeEmail == "james@stitchsocial.me") {
                println("USER SERVICE: ⚠️ Cannot unfollow founder (James Fortune)")
                handleError("You cannot unfollow the founder")
                return false
            }

            println("USER SERVICE: User $followerID unfollowing $followeeID")

            // Delete follow relationship from follower's subcollection
            db.collection("users")
                .document(followerID)
                .collection("following")
                .document(followeeID)
                .delete()
                .await()

            // Delete reverse relationship from followee's subcollection
            db.collection("users")
                .document(followeeID)
                .collection("followers")
                .document(followerID)
                .delete()
                .await()

            // Update follower count atomically
            db.collection("users").document(followeeID)
                .update("followerCount", FieldValue.increment(-1))
                .await()

            // Update following count atomically
            db.collection("users").document(followerID)
                .update("followingCount", FieldValue.increment(-1))
                .await()

            updateUserCaches(followerID, followeeID)
            println("USER SERVICE: ✅ Unfollow completed")
            true

        } catch (e: Exception) {
            handleError("Failed to unfollow user: ${e.message}")
            false
        }
    }

    /**
     * ✅ FIXED: Using subcollection structure users/{followerID}/following/{followeeID}
     * Check if user is following another user
     */
    suspend fun isFollowing(followerID: String, followeeID: String): Boolean {
        return try {
            val followDoc = db.collection("users")
                .document(followerID)
                .collection("following")
                .document(followeeID)
                .get()
                .await()

            followDoc.exists()

        } catch (e: Exception) {
            println("USER SERVICE: Error checking follow status: ${e.message}")
            false
        }
    }

    /**
     * ✅ FIXED: Using subcollection structure users/{userID}/following
     * Get list of users that the given user is following
     */
    suspend fun getFollowing(userID: String, limit: Int = 50): List<BasicUserInfo> {
        return try {
            val followDocs = db.collection("users")
                .document(userID)
                .collection("following")
                .limit(limit.toLong())
                .get()
                .await()

            val followeeIDs = followDocs.documents.map { it.id }
            if (followeeIDs.isEmpty()) return emptyList()

            batchLoadUsers(followeeIDs).values.toList()

        } catch (e: Exception) {
            println("USER SERVICE: Error loading following list: ${e.message}")
            emptyList()
        }
    }

    /**
     * ✅ FIXED: Using subcollection structure users/{userID}/followers
     * Get list of users following the given user
     */
    suspend fun getFollowers(userID: String, limit: Int = 50): List<BasicUserInfo> {
        return try {
            val followDocs = db.collection("users")
                .document(userID)
                .collection("followers")
                .limit(limit.toLong())
                .get()
                .await()

            val followerIDs = followDocs.documents.map { it.id }
            if (followerIDs.isEmpty()) return emptyList()

            batchLoadUsers(followerIDs).values.toList()

        } catch (e: Exception) {
            println("USER SERVICE: Error loading followers list: ${e.message}")
            emptyList()
        }
    }

    /**
     * ✅ FIXED: Using subcollection structure (2 queries)
     * Refresh follower/following counts for a user by counting actual relationships
     * Ensures counts are accurate after follow/unfollow operations
     */
    suspend fun refreshFollowerCounts(userID: String) {
        try {
            println("🔄 USER SERVICE: Refreshing follower counts for user $userID")

            // Count actual followers
            val followersSnapshot = db.collection("users")
                .document(userID)
                .collection("followers")
                .get()
                .await()

            // Count actual following
            val followingSnapshot = db.collection("users")
                .document(userID)
                .collection("following")
                .get()
                .await()

            val actualFollowerCount = followersSnapshot.documents.size
            val actualFollowingCount = followingSnapshot.documents.size

            // Update the user document with accurate counts
            db.collection("users")
                .document(userID)
                .update(
                    mapOf(
                        "followerCount" to actualFollowerCount,
                        "followingCount" to actualFollowingCount,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()

            println("✅ USER SERVICE: Updated counts - Followers: $actualFollowerCount, Following: $actualFollowingCount")

        } catch (e: Exception) {
            println("⚠️ USER SERVICE: Failed to refresh follower counts for $userID: ${e.message}")
            throw e
        }
    }

    // ===== ENGAGEMENT STATS & CLOUT SYSTEM =====

    /**
     * Update user engagement statistics (clout, views, etc.)
     */
    suspend fun updateEngagementStats(
        userID: String,
        cloutChange: Int = 0,
        viewsChange: Int = 0,
        hypesChange: Int = 0,
        coolsChange: Int = 0
    ): Boolean {
        return try {
            val updates = hashMapOf<String, Any>()

            if (cloutChange != 0) updates["clout"] = FieldValue.increment(cloutChange.toLong())
            if (viewsChange != 0) updates["totalViews"] = FieldValue.increment(viewsChange.toLong())
            if (hypesChange != 0) updates["totalHypes"] = FieldValue.increment(hypesChange.toLong())
            if (coolsChange != 0) updates["totalCools"] = FieldValue.increment(coolsChange.toLong())

            if (updates.isNotEmpty()) {
                updates["updatedAt"] = FieldValue.serverTimestamp()
                db.collection("users").document(userID).update(updates).await()
                invalidateUserCache(userID)
                println("USER SERVICE: ✅ Engagement stats updated for $userID")
                true
            } else {
                false
            }

        } catch (e: Exception) {
            println("USER SERVICE: Error updating engagement stats: ${e.message}")
            false
        }
    }

    /**
     * Award clout to a user and check for tier advancement
     */
    suspend fun awardClout(userID: String, amount: Int): Boolean {
        val result = updateEngagementStats(userID = userID, cloutChange = amount)
        if (result) {
            checkAndAdvanceTier(userID)
        }
        return result
    }

    /**
     * Check if user qualifies for a higher tier and update if so
     */
    private suspend fun checkAndAdvanceTier(userID: String) {
        try {
            val userDoc = db.collection("users").document(userID).get().await()
            val currentClout = (userDoc.getLong("clout") ?: 0).toInt()
            val currentTierRaw = userDoc.getString("tier") ?: "rookie"
            val currentTier = UserTier.fromRawValue(currentTierRaw) ?: UserTier.ROOKIE

            // Don't change founder/co-founder tiers
            if (currentTier.isFounderTier) return

            val correctTier = UserTier.tierForClout(currentClout)
            if (correctTier != currentTier && correctTier.level > currentTier.level) {
                db.collection("users").document(userID).update(
                    mapOf(
                        "tier" to correctTier.rawValue,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
                invalidateUserCache(userID)
                println("USER SERVICE: Tier advanced for $userID: ${currentTier.displayName} -> ${correctTier.displayName} (clout: $currentClout)")
            }
        } catch (e: Exception) {
            println("USER SERVICE: Tier check failed (non-fatal): ${e.message}")
        }
    }

    /**
     * Get user's current clout score
     */
    suspend fun getUserClout(userID: String): Int {
        return try {
            val userDoc = db.collection("users").document(userID).get().await()
            (userDoc.getLong("clout") ?: 0).toInt()
        } catch (e: Exception) {
            println("USER SERVICE: Error getting user clout: ${e.message}")
            0
        }
    }

    // ===== USER SEARCH & DISCOVERY FUNCTIONALITY =====

    /**
     * Search users by username or display name with caching
     */
    suspend fun searchUsers(query: String, limit: Int = 20): List<BasicUserInfo> {
        return try {
            if (query.length < 2) return emptyList()

            println("USER SERVICE: 🔍 Searching users with query: '$query'")

            // Check cache first
            val cacheKey = "${query.lowercase()}_$limit"
            searchCache[cacheKey]?.let { (cachedResults, timestamp) ->
                if (System.currentTimeMillis() - timestamp < cacheExpiration) {
                    println("USER SERVICE: ✅ Returning cached search results (${cachedResults.size})")
                    return cachedResults
                }
            }

            // Perform multiple search strategies
            val results = mutableSetOf<BasicUserInfo>()

            // Strategy 1: Username prefix search (most common)
            val usernameResults = searchUsersByUsername(query, limit)
            results.addAll(usernameResults)

            // Strategy 2: Display name search (if we haven't reached limit)
            if (results.size < limit) {
                val displayNameResults = searchUsersByDisplayName(query, limit - results.size)
                results.addAll(displayNameResults)
            }

            // Strategy 3: Bio keyword search (if still under limit)
            if (results.size < limit) {
                val bioResults = searchUsersByBio(query, limit - results.size)
                results.addAll(bioResults)
            }

            val finalResults = results.take(limit)

            // Cache results
            if (searchCache.size >= maxCacheEntries) {
                // Remove oldest entries
                val oldest = searchCache.entries.minByOrNull { it.value.second }
                oldest?.let { searchCache.remove(it.key) }
            }
            searchCache[cacheKey] = Pair(finalResults, System.currentTimeMillis())

            println("USER SERVICE: ✅ Found ${finalResults.size} users for '$query'")
            finalResults

        } catch (e: Exception) {
            println("USER SERVICE: ❌ Search error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Search users by username prefix
     */
    private suspend fun searchUsersByUsername(query: String, limit: Int): List<BasicUserInfo> {
        return try {
            val queryLower = query.lowercase()

            val users = db.collection("users")
                .whereGreaterThanOrEqualTo("username", queryLower)
                .whereLessThanOrEqualTo("username", queryLower + "\uf8ff")
                .limit(limit.toLong())
                .get()
                .await()

            users.documents.mapNotNull { BasicUserInfo.fromFirebaseDocument(it) }

        } catch (e: Exception) {
            println("USER SERVICE: Username search error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Search users by display name
     */
    private suspend fun searchUsersByDisplayName(query: String, limit: Int): List<BasicUserInfo> {
        return try {
            val users = db.collection("users")
                .orderBy("displayName")
                .limit(limit.toLong())
                .get()
                .await()

            users.documents.mapNotNull { doc ->
                val user = BasicUserInfo.fromFirebaseDocument(doc)
                if (user?.displayName?.contains(query, ignoreCase = true) == true) user else null
            }

        } catch (e: Exception) {
            println("USER SERVICE: Display name search error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Search users by bio keywords
     */
    private suspend fun searchUsersByBio(query: String, limit: Int): List<BasicUserInfo> {
        return try {
            val users = db.collection("users")
                .limit(limit.toLong())
                .get()
                .await()

            users.documents.mapNotNull { doc ->
                val user = BasicUserInfo.fromFirebaseDocument(doc)
                if (user?.bio?.contains(query, ignoreCase = true) == true) user else null
            }

        } catch (e: Exception) {
            println("USER SERVICE: Bio search error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Clear search cache
     */
    private fun clearSearchCache() {
        searchCache.clear()
        println("USER SERVICE: Search cache cleared")
    }

    /**
     * ✅ FIXED: Using subcollection structure users/{userID}/following
     * Get suggested users for discovery (trending, new, etc.)
     */
    suspend fun getSuggestedUsers(forUserID: String, limit: Int = 20): List<BasicUserInfo> {
        return try {
            println("USER SERVICE: Getting suggested users for $forUserID")

            // Get users the current user is already following
            val followingDocs = db.collection("users")
                .document(forUserID)
                .collection("following")
                .get()
                .await()

            val followingIDs = followingDocs.documents.map { it.id }.toSet()

            // Get suggested users - prioritize by follower count
            val suggestedUsers = db.collection("users")
                .orderBy("followerCount", Query.Direction.DESCENDING)
                .limit((limit * 3).toLong())
                .get()
                .await()

            val users = suggestedUsers.documents.mapNotNull { doc ->
                val user = BasicUserInfo.fromFirebaseDocument(doc)
                if (user != null && user.id != forUserID && user.id !in followingIDs) {
                    user
                } else {
                    null
                }
            }.take(limit)

            println("USER SERVICE: Found ${users.size} suggested users")
            users

        } catch (e: Exception) {
            println("USER SERVICE: Failed to get suggested users: ${e.message}")
            emptyList()
        }
    }

    // ===== PRIVATE HELPER METHODS =====

    private fun isValidUsername(username: String): Boolean {
        return username.length in 3..20 &&
                username.matches(Regex("^[a-zA-Z0-9_]+$")) &&
                !username.startsWith("_") &&
                !username.endsWith("_")
    }

    private suspend fun updateUserCaches(followerID: String, followeeID: String) {
        invalidateUserCache(followerID)
        invalidateUserCache(followeeID)
        clearSearchCache()
    }

    private fun invalidateUserCache(userID: String) {
        println("USER SERVICE: Invalidated cache for user $userID")
    }

    private fun handleError(message: String) {
        val error = StitchError.NetworkError(message)
        _lastError.value = error
        println("USER SERVICE: $message")
    }

    fun clearError() {
        _lastError.value = null
    }

    fun helloWorldTest() {
        println("USER SERVICE: Hello World - Enhanced user management ready!")
        println("USER SERVICE: Features: Profile editing, image upload, social following, engagement stats, user search, discovery")
        println("USER SERVICE: Integration: Cache-aware, atomic updates, social discovery, search optimization")
        println("USER SERVICE: ✅ FIXED: Now using subcollection structure users/{userID}/following/{followeeID}")
    }
}