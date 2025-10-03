/*
 * UserService.kt - COMPLETE WITH SEARCH AND DISCOVERY INTEGRATION
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Complete User Management with Profile Editing & Search
 * Dependencies: Firebase, Foundation Layer, SimplifiedCachingService
 * Features: Profile editing, image upload, social following, engagement stats, user search, discovery integration
 *
 * ENHANCED: Added user search functionality for DiscoveryView integration
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

    // ===== ENHANCED USER PROFILE LOADING WITH DEBUG =====

    /**
     * Get user profile with enhanced debugging and cache integration
     */
    suspend fun getUserProfile(userID: String): BasicUserInfo? {
        return try {
            println("USER SERVICE: Loading profile for user $userID")
            println("USER SERVICE: Database: stitchfin")

            _isLoading.value = true

            // Check cache first
            val cachedUser = cachingService.getCachedUser(userID)
            if (cachedUser != null) {
                println("USER SERVICE: Found cached user: ${cachedUser.username}")
                return cachedUser
            }

            // Query Firebase with debug logging
            println("USER SERVICE: Querying Firebase collection 'users' document '$userID'")
            val document = db.collection("users").document(userID).get().await()

            println("USER SERVICE: Document exists: ${document.exists()}")
            println("USER SERVICE: Document path: ${document.reference.path}")

            if (document.exists()) {
                val data = document.data
                println("USER SERVICE: Document has data: ${data != null}")

                if (data != null) {
                    println("USER SERVICE: Data keys: ${data.keys.joinToString(", ")}")
                    println("USER SERVICE: username = ${data["username"]}")
                    println("USER SERVICE: displayName = ${data["displayName"]}")
                    println("USER SERVICE: bio = ${data["bio"]}")
                    println("USER SERVICE: tier = ${data["tier"]}")
                }

                val userInfo = BasicUserInfo.fromFirebaseDocument(document)

                if (userInfo != null) {
                    println("USER SERVICE: Successfully parsed user: ${userInfo.username}")
                    cachingService.cacheUser(userInfo, SimplifiedCachingService.CachePriority.NORMAL)
                    return userInfo
                } else {
                    println("USER SERVICE: Failed to parse document")
                    return null
                }
            } else {
                println("USER SERVICE: Document does not exist")
                return null
            }

        } catch (e: Exception) {
            println("USER SERVICE: Exception: ${e.message}")
            handleError("Failed to get user profile: ${e.message}")
            return null
        } finally {
            _isLoading.value = false
        }
    }

    // ===== PROFILE EDITING METHODS WITH USERNAME AND IMAGE SUPPORT =====

    /**
     * Update user profile with validation and cache sync - FIXED WITH USERNAME
     */
    suspend fun updateProfile(
        userID: String,
        displayName: String? = null,
        bio: String? = null,
        username: String? = null,
        isPrivate: Boolean? = null,
        profileImageURL: String? = null
    ): BasicUserInfo? {
        return try {
            _isLoading.value = true
            println("USER SERVICE: Updating profile for user $userID")

            // Validate inputs
            if (displayName != null && (displayName.isBlank() || displayName.length > 30)) {
                throw StitchError.ValidationError("Display name must be 1-30 characters")
            }

            if (bio != null && bio.length > 150) {
                throw StitchError.ValidationError("Bio cannot exceed 150 characters")
            }

            if (username != null) {
                if (!isValidUsername(username)) {
                    throw StitchError.ValidationError("Username must be 3-20 characters, alphanumeric and underscores only")
                }

                // Check username availability
                if (!checkUsernameAvailability(username, userID)) {
                    throw StitchError.ValidationError("Username '$username' is already taken")
                }
            }

            // Build update data
            val updates = mutableMapOf<String, Any>(
                "updatedAt" to Timestamp.now()
            )

            displayName?.let {
                updates["displayName"] = it
                println("USER SERVICE: 📝 Updating displayName to: $it")
            }
            bio?.let {
                updates["bio"] = it
                println("USER SERVICE: 📝 Updating bio to: $it")
            }
            username?.let {
                updates["username"] = it
                println("USER SERVICE: 👤 Updating username to: $it")
            }
            isPrivate?.let {
                updates["isPrivate"] = it
                println("USER SERVICE: 🔒 Updating privacy to: $it")
            }
            profileImageURL?.let {
                updates["profileImageURL"] = it
                println("USER SERVICE: 🖼️ Updating profile image to: $it")
            }

            // Update Firebase
            db.collection("users").document(userID).update(updates).await()
            println("USER SERVICE: Profile updated for user $userID")

            // Get updated user and refresh cache
            val updatedUser = getUserProfile(userID)
            if (updatedUser != null) {
                cachingService.cacheUser(updatedUser, SimplifiedCachingService.CachePriority.HIGH)
            }

            updatedUser

        } catch (e: Exception) {
            handleError("Failed to update profile: ${e.message}")
            null
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Complete profile editing with image upload support
     */
    suspend fun updateProfileWithImage(
        userID: String,
        displayName: String? = null,
        bio: String? = null,
        imageUri: Uri? = null
    ): BasicUserInfo? {
        return try {
            _isLoading.value = true
            println("USER SERVICE: 🔄 Updating profile with image for user $userID")
            println("USER SERVICE: 📝 DisplayName: ${displayName ?: "unchanged"}")
            println("USER SERVICE: 📝 Bio: ${bio ?: "unchanged"}")
            println("USER SERVICE: 🖼️ Image: ${if (imageUri != null) "uploading new image" else "no image change"}")

            var imageURL: String? = null

            // Step 1: Upload image if provided
            if (imageUri != null) {
                println("USER SERVICE: 📤 Uploading new profile image...")
                imageURL = uploadProfileImageFromUri(userID, imageUri)
                println("USER SERVICE: ✅ Image uploaded: $imageURL")
            }

            // Step 2: Update profile with all fields
            val updatedUser = updateProfile(
                userID = userID,
                displayName = displayName,
                bio = bio,
                profileImageURL = imageURL
            )

            if (updatedUser != null) {
                println("USER SERVICE: ✅ Profile updated successfully!")
            } else {
                println("USER SERVICE: ❌ Profile update failed")
            }

            updatedUser

        } catch (e: Exception) {
            println("USER SERVICE: ❌ Error updating profile with image: ${e.message}")
            handleError("Failed to update profile: ${e.message}")
            null
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Upload profile image from URI with compression
     */
    private suspend fun uploadProfileImageFromUri(userID: String, imageUri: Uri): String {
        return withContext(Dispatchers.IO) {
            try {
                println("USER SERVICE: 🔄 Processing image for upload...")

                // Read image from URI
                val inputStream = context.contentResolver.openInputStream(imageUri)
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (originalBitmap == null) {
                    throw StitchError.StorageError("Failed to decode image")
                }

                // Resize to max 512x512 for profile images
                val resizedBitmap = resizeBitmap(originalBitmap, 512, 512)

                // Compress to JPEG with 80% quality
                val outputStream = ByteArrayOutputStream()
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                val compressedData = outputStream.toByteArray()

                println("USER SERVICE: 📊 Image compressed: ${compressedData.size / 1024}KB")

                // Upload to Firebase Storage
                val imageRef = storage.reference.child("profile_images/$userID.jpg")
                val uploadTask = imageRef.putBytes(compressedData).await()
                val downloadUrl = imageRef.downloadUrl.await()

                println("USER SERVICE: ✅ Upload completed: $downloadUrl")
                downloadUrl.toString()

            } catch (e: Exception) {
                println("USER SERVICE: ❌ Image upload failed: ${e.message}")
                throw StitchError.StorageError("Image upload failed: ${e.message}")
            }
        }
    }

    /**
     * Resize bitmap maintaining aspect ratio
     */
    private fun resizeBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // Already small enough
        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }

        // Calculate scale factor
        val scaleWidth = maxWidth.toFloat() / width
        val scaleHeight = maxHeight.toFloat() / height
        val scale = min(scaleWidth, scaleHeight)

        // Calculate new dimensions
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Check username availability
     */
    suspend fun checkUsernameAvailability(username: String, excludeUserID: String? = null): Boolean {
        return try {
            val query = db.collection("users")
                .whereEqualTo("username", username)
                .limit(1)
                .get()
                .await()

            if (query.isEmpty) {
                true
            } else {
                // Check if the found username belongs to the current user (for updates)
                val foundUser = query.documents.first()
                excludeUserID != null && foundUser.id == excludeUserID
            }

        } catch (e: Exception) {
            println("USER SERVICE: Error checking username availability: ${e.message}")
            false
        }
    }

    /**
     * Upload profile image (legacy method for compatibility)
     */
    suspend fun uploadProfileImage(userID: String, imageData: ByteArray): String? {
        return try {
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

    // ===== USER SEARCH & DISCOVERY FUNCTIONALITY =====

    /**
     * Search users by username or display name with caching
     * ENHANCED: Full search functionality for DiscoveryView integration
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

            // Strategy 3: Bio keyword search (if we still need more)
            if (results.size < limit) {
                val bioResults = searchUsersByBio(query, limit - results.size)
                results.addAll(bioResults)
            }

            val finalResults = results.take(limit)

            // Cache results
            if (searchCache.size >= maxCacheEntries) {
                // Remove oldest cache entry
                val oldestKey = searchCache.minByOrNull { it.value.second }?.key
                oldestKey?.let { searchCache.remove(it) }
            }
            searchCache[cacheKey] = Pair(finalResults, System.currentTimeMillis())

            println("USER SERVICE: ✅ Found ${finalResults.size} users matching '$query'")
            finalResults

        } catch (e: Exception) {
            println("USER SERVICE: ❌ Error searching users: ${e.message}")
            handleError("User search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Search users by username (primary search method)
     */
    private suspend fun searchUsersByUsername(query: String, limit: Int): List<BasicUserInfo> {
        return try {
            val normalizedQuery = query.lowercase().trim()

            val usernameQuery = db.collection("users")
                .whereGreaterThanOrEqualTo("username", normalizedQuery)
                .whereLessThanOrEqualTo("username", normalizedQuery + "\uf8ff")
                .limit(limit.toLong())

            val snapshot = usernameQuery.get().await()

            val users = snapshot.documents.mapNotNull { doc ->
                BasicUserInfo.fromFirebaseDocument(doc)
            }

            println("USER SERVICE: Found ${users.size} users by username search")
            users

        } catch (e: Exception) {
            println("USER SERVICE: Username search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Search users by display name (secondary search method)
     */
    private suspend fun searchUsersByDisplayName(query: String, limit: Int): List<BasicUserInfo> {
        return try {
            val normalizedQuery = query.lowercase().trim()

            // Firebase doesn't support case-insensitive search on displayName directly
            // So we'll get all users and filter locally (for small datasets)
            // TODO: Implement Algolia or similar for better text search in production

            val snapshot = db.collection("users")
                .limit(200) // Limit to prevent huge downloads
                .get()
                .await()

            val users = snapshot.documents.mapNotNull { doc ->
                val user = BasicUserInfo.fromFirebaseDocument(doc)
                // Filter by display name containing query
                if (user != null && user.displayName.lowercase().contains(normalizedQuery)) {
                    user
                } else {
                    null
                }
            }.take(limit)

            println("USER SERVICE: Found ${users.size} users by display name search")
            users

        } catch (e: Exception) {
            println("USER SERVICE: Display name search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Search users by bio keywords (tertiary search method)
     */
    private suspend fun searchUsersByBio(query: String, limit: Int): List<BasicUserInfo> {
        return try {
            val normalizedQuery = query.lowercase().trim()

            // Similar to display name search - get recent users and filter locally
            val snapshot = db.collection("users")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(200)
                .get()
                .await()

            val users = snapshot.documents.mapNotNull { doc ->
                val user = BasicUserInfo.fromFirebaseDocument(doc)
                // Filter by bio containing query
                if (user != null && user.bio.lowercase().contains(normalizedQuery)) {
                    user
                } else {
                    null
                }
            }.take(limit)

            println("USER SERVICE: Found ${users.size} users by bio search")
            users

        } catch (e: Exception) {
            println("USER SERVICE: Bio search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get search suggestions based on query
     */
    suspend fun getUserSearchSuggestions(query: String): List<String> {
        return try {
            if (query.length < 2) return emptyList()

            val normalizedQuery = query.lowercase().trim()
            val suggestions = mutableSetOf<String>()

            // Get recent users to generate suggestions
            val snapshot = db.collection("users")
                .orderBy("lastActiveAt", Query.Direction.DESCENDING)
                .limit(50)
                .get()
                .await()

            snapshot.documents.forEach { doc ->
                val username = doc.getString("username")
                val displayName = doc.getString("displayName")

                username?.let {
                    if (it.startsWith(normalizedQuery) && it != normalizedQuery) {
                        suggestions.add("@$it")
                    }
                }

                displayName?.let {
                    if (it.lowercase().startsWith(normalizedQuery) && it.lowercase() != normalizedQuery) {
                        suggestions.add(it)
                    }
                }
            }

            suggestions.take(5).toList()

        } catch (e: Exception) {
            println("USER SERVICE: Error getting user suggestions: ${e.message}")
            emptyList()
        }
    }

    /**
     * Clear search cache
     */
    fun clearSearchCache() {
        searchCache.clear()
        println("USER SERVICE: Search cache cleared")
    }

    // ===== SOCIAL FOLLOWING SYSTEM =====

    /**
     * Follow a user with atomic counter updates
     */
    suspend fun followUser(followerID: String, followeeID: String): Boolean {
        return try {
            if (followerID == followeeID) {
                println("USER SERVICE: Cannot follow yourself")
                return false
            }

            val isCurrentlyFollowing = isFollowing(followerID, followeeID)
            if (isCurrentlyFollowing) {
                println("USER SERVICE: Already following $followeeID")
                return true
            }

            // Use batch write for atomic updates
            val batch = db.batch()

            // Create follow relationship document
            val followDocID = "${followerID}_${followeeID}"
            val followRef = db.collection("following").document(followDocID)
            val followData = mapOf(
                "followerID" to followerID,
                "followingID" to followeeID,
                "isActive" to true,
                "createdAt" to Timestamp.now()
            )
            batch.set(followRef, followData)

            // Update follower's following count
            val followerRef = db.collection("users").document(followerID)
            batch.update(followerRef, "followingCount", FieldValue.increment(1))

            // Update followee's follower count
            val followeeRef = db.collection("users").document(followeeID)
            batch.update(followeeRef, "followerCount", FieldValue.increment(1))

            // Commit all updates atomically
            batch.commit().await()

            // Update caches
            updateUserCaches(followerID, followeeID)

            println("USER SERVICE: $followerID successfully followed $followeeID")
            true

        } catch (e: Exception) {
            handleError("Failed to follow user: ${e.message}")
            false
        }
    }

    /**
     * Unfollow a user with atomic counter updates
     */
    suspend fun unfollowUser(followerID: String, followeeID: String): Boolean {
        return try {
            val isCurrentlyFollowing = isFollowing(followerID, followeeID)
            if (!isCurrentlyFollowing) {
                println("USER SERVICE: Not currently following $followeeID")
                return true
            }

            // Use batch write for atomic updates
            val batch = db.batch()

            // Remove follow relationship document
            val followDocID = "${followerID}_${followeeID}"
            val followRef = db.collection("following").document(followDocID)
            batch.delete(followRef)

            // Update follower's following count (prevent negative)
            val followerRef = db.collection("users").document(followerID)
            batch.update(followerRef, "followingCount", FieldValue.increment(-1))

            // Update followee's follower count (prevent negative)
            val followeeRef = db.collection("users").document(followeeID)
            batch.update(followeeRef, "followerCount", FieldValue.increment(-1))

            // Commit all updates atomically
            batch.commit().await()

            // Update caches
            updateUserCaches(followerID, followeeID)

            println("USER SERVICE: $followerID successfully unfollowed $followeeID")
            true

        } catch (e: Exception) {
            handleError("Failed to unfollow user: ${e.message}")
            false
        }
    }

    /**
     * Check if user is following another user
     */
    suspend fun isFollowing(followerID: String, followeeID: String): Boolean {
        return try {
            val followDocID = "${followerID}_${followeeID}"
            val document = db.collection("following").document(followDocID).get().await()

            document.exists() && (document.data?.get("isActive") as? Boolean ?: false)

        } catch (e: Exception) {
            println("USER SERVICE: Error checking following status: ${e.message}")
            false
        }
    }

    /**
     * Get list of users that this user is following
     */
    suspend fun getFollowingList(userID: String, limit: Int = 50): List<BasicUserInfo> {
        return try {
            println("USER SERVICE: Loading following list for user $userID")

            val followingSnapshot = db.collection("following")
                .whereEqualTo("followerID", userID)
                .whereEqualTo("isActive", true)
                .limit(limit.toLong())
                .get()
                .await()

            val following = mutableListOf<BasicUserInfo>()

            for (doc in followingSnapshot.documents) {
                val followingUserID = doc.getString("followingID")
                if (followingUserID != null) {
                    getUserProfile(followingUserID)?.let { user ->
                        following.add(user)
                    }
                }
            }

            println("USER SERVICE: Loaded ${following.size} following users")
            following

        } catch (e: Exception) {
            println("USER SERVICE: Failed to get following list: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get list of users following this user
     */
    suspend fun getFollowersList(userID: String, limit: Int = 50): List<BasicUserInfo> {
        return try {
            println("USER SERVICE: Loading followers list for user $userID")

            val followersSnapshot = db.collection("following")
                .whereEqualTo("followingID", userID)
                .whereEqualTo("isActive", true)
                .limit(limit.toLong())
                .get()
                .await()

            val followers = mutableListOf<BasicUserInfo>()

            for (doc in followersSnapshot.documents) {
                val followerID = doc.getString("followerID")
                if (followerID != null) {
                    getUserProfile(followerID)?.let { user ->
                        followers.add(user)
                    }
                }
            }

            println("USER SERVICE: Loaded ${followers.size} followers")
            followers

        } catch (e: Exception) {
            println("USER SERVICE: Failed to get followers list: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get following IDs only (for feed filtering)
     */
    suspend fun getFollowingIDs(userID: String): List<String> {
        return try {
            println("USER SERVICE: Loading following IDs for user: $userID")

            val followingSnapshot = db.collection("following")
                .whereEqualTo("followerID", userID)
                .whereEqualTo("isActive", true)
                .get()
                .await()

            val followingIDs = followingSnapshot.documents.mapNotNull { doc ->
                doc.getString("followingID")
            }

            println("USER SERVICE: Found ${followingIDs.size} following users")
            followingIDs

        } catch (e: Exception) {
            println("USER SERVICE: Failed to get following IDs: ${e.message}")
            emptyList()
        }
    }

    // ===== SOCIAL STATS UPDATES =====

    /**
     * Increment user's video count
     */
    suspend fun incrementVideoCount(userID: String) {
        try {
            db.collection("users").document(userID)
                .update("videoCount", FieldValue.increment(1))
                .await()

            invalidateUserCache(userID)
            println("USER SERVICE: Incremented video count for user $userID")

        } catch (e: Exception) {
            println("USER SERVICE: Error incrementing video count: ${e.message}")
        }
    }

    /**
     * Update user engagement statistics
     */
    suspend fun updateEngagementStats(userID: String, newHypes: Int, newCools: Int) {
        try {
            val updates = mapOf(
                "totalHypesReceived" to FieldValue.increment(newHypes.toLong()),
                "totalCoolsReceived" to FieldValue.increment(newCools.toLong()),
                "lastEngagementAt" to Timestamp.now(),
                "updatedAt" to Timestamp.now()
            )

            db.collection("users").document(userID).update(updates).await()
            invalidateUserCache(userID)

            println("USER SERVICE: Updated engagement stats for user $userID (+$newHypes hypes, +$newCools cools)")

        } catch (e: Exception) {
            println("USER SERVICE: Error updating engagement stats: ${e.message}")
        }
    }

    /**
     * Update last active timestamp
     */
    suspend fun updateLastActive(userID: String) {
        try {
            db.collection("users").document(userID)
                .update("lastActiveAt", Timestamp.now())
                .await()

            println("USER SERVICE: Updated last active for user $userID")

        } catch (e: Exception) {
            println("USER SERVICE: Error updating last active: ${e.message}")
        }
    }

    // ===== DISCOVERY & RECOMMENDATIONS =====

    /**
     * Get suggested users for discovery (users with similar interests)
     */
    suspend fun getSuggestedUsers(forUserID: String, limit: Int = 20): List<BasicUserInfo> {
        return try {
            println("USER SERVICE: Getting suggested users for $forUserID")

            // Get user's current following to exclude them
            val followingIDs = getFollowingIDs(forUserID).toSet()

            // Strategy: Get popular users who are not being followed
            val suggestedUsers = db.collection("users")
                .whereGreaterThan("followerCount", 10) // Users with some following
                .orderBy("followerCount", Query.Direction.DESCENDING)
                .limit((limit * 3).toLong()) // Get more to filter out following
                .get()
                .await()

            val users = suggestedUsers.documents.mapNotNull { doc ->
                val user = BasicUserInfo.fromFirebaseDocument(doc)
                // Exclude current user and users already being followed
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
        clearSearchCache() // Clear search cache when user relationships change
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
    }
}