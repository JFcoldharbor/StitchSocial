/*
 * UserService.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Complete User Management Implementation
 * Dependencies: Firebase, Foundation Layer only
 * Features: ProfileView integration, social relationships, tier progression
 *
 * BLUEPRINT: UserService.swift exact translation
 */

package com.example.stitchsocialclub.services

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.example.stitchsocialclub.foundation.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.*

/**
 * Complete UserService implementation for ProfileView integration
 * Supports all user operations, social relationships, and tier progression
 */
class UserService {

    // Use "stitchfin" database for consistency with VideoService
    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val storage = FirebaseStorage.getInstance()

    // Add authentication service - REMOVED to avoid circular dependency
    // private val authService = AuthService()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastError = MutableStateFlow<StitchError?>(null)
    val lastError: StateFlow<StitchError?> = _lastError.asStateFlow()

    // MARK: - Enums

    enum class UserTier(val rawValue: String) {
        ROOKIE("rookie"),
        RISING("rising"),
        VETERAN("veteran"),
        INFLUENCER("influencer"),
        ELITE("elite"),
        PARTNER("partner"),
        LEGENDARY("legendary"),
        TOP_CREATOR("top_creator"),
        FOUNDER("founder"),
        CO_FOUNDER("co_founder");

        val displayName: String
            get() = when (this) {
                ROOKIE -> "Rookie"
                RISING -> "Rising"
                VETERAN -> "Veteran"
                INFLUENCER -> "Influencer"
                ELITE -> "Elite"
                PARTNER -> "Partner"
                LEGENDARY -> "Legendary"
                TOP_CREATOR -> "Top Creator"
                FOUNDER -> "Founder"
                CO_FOUNDER -> "Co-Founder"
            }

        val cloutRange: IntRange
            get() = when (this) {
                ROOKIE -> 0..999
                RISING -> 1000..4999
                VETERAN -> 5000..9999
                INFLUENCER -> 10000..19999
                ELITE -> 20000..49999
                PARTNER -> 50000..99999
                LEGENDARY -> 100000..499999
                TOP_CREATOR -> 500000..Int.MAX_VALUE
                FOUNDER -> 0..Int.MAX_VALUE
                CO_FOUNDER -> 0..Int.MAX_VALUE
            }

        val isAchievableTier: Boolean
            get() = when (this) {
                FOUNDER, CO_FOUNDER -> false
                else -> true
            }
    }

    // MARK: - Data Classes

    data class BasicUserInfo(
        val id: String,
        val username: String,
        val displayName: String,
        val profileImageURL: String?,
        val bio: String?,
        val tier: UserTier,
        val clout: Int,
        val isVerified: Boolean,
        val isPrivate: Boolean = false,
        val followerCount: Int = 0,
        val followingCount: Int = 0,
        val videoCount: Int = 0,
        val createdAt: Date = Date()
    ) {
        companion object {
            fun fromFirebaseDocument(doc: DocumentSnapshot): BasicUserInfo? {
                return try {
                    val data = doc.data ?: return null

                    BasicUserInfo(
                        id = doc.id,
                        username = data["username"] as? String ?: "unknown",
                        displayName = data["displayName"] as? String ?: "User",
                        profileImageURL = data["profileImageURL"] as? String,
                        bio = data["bio"] as? String,
                        tier = UserTier.valueOf((data["tier"] as? String)?.uppercase() ?: "ROOKIE"),
                        clout = (data["clout"] as? Long)?.toInt() ?: 0,
                        isVerified = data["isVerified"] as? Boolean ?: false,
                        isPrivate = data["isPrivate"] as? Boolean ?: false,
                        followerCount = (data["followerCount"] as? Long)?.toInt() ?: 0,
                        followingCount = (data["followingCount"] as? Long)?.toInt() ?: 0,
                        videoCount = (data["videoCount"] as? Long)?.toInt() ?: 0,
                        createdAt = (data["createdAt"] as? Timestamp)?.toDate() ?: Date()
                    )
                } catch (e: Exception) {
                    println("USER SERVICE: Failed to parse user document ${doc.id}: ${e.message}")
                    null
                }
            }
        }
    }

    data class ProfileVideoMetadata(
        val id: String,
        val title: String,
        val thumbnailURL: String,
        val duration: Double,
        val conversationDepth: Int, // 0=Thread, 1=Stitch, 2+=Reply
        val viewCount: Int,
        val createdAt: Date,
        val isProcessing: Boolean = false
    ) {
        companion object {
            fun fromFirebaseDocument(doc: DocumentSnapshot): ProfileVideoMetadata? {
                return try {
                    val data = doc.data ?: return null

                    ProfileVideoMetadata(
                        id = doc.id,
                        title = data["title"] as? String ?: "Untitled",
                        thumbnailURL = data["thumbnailURL"] as? String ?: "",
                        duration = (data["duration"] as? Number)?.toDouble() ?: 0.0,
                        conversationDepth = (data["conversationDepth"] as? Long)?.toInt() ?: 0,
                        viewCount = (data["viewCount"] as? Long)?.toInt() ?: 0,
                        createdAt = (data["createdAt"] as? Timestamp)?.toDate() ?: Date(),
                        isProcessing = data["isProcessing"] as? Boolean ?: false
                    )
                } catch (e: Exception) {
                    println("USER SERVICE: Failed to parse video document ${doc.id}: ${e.message}")
                    null
                }
            }
        }
    }

    data class TierProgress(
        val currentTier: UserTier,
        val currentClout: Int,
        val nextTier: UserTier?,
        val cloutNeeded: Int,
        val progressPercentage: Double
    )

    data class UserStats(
        val totalVideos: Int = 0,
        val totalViews: Int = 0,
        val totalLikes: Int = 0,
        val averageEngagement: Double = 0.0,
        val weeklyGrowth: Double = 0.0
    )

    data class SpecialUserEntry(
        val email: String,
        val startingClout: Int,
        val tier: UserTier,
        val customTitle: String?,
        val customBio: String?,
        val isAutoFollowed: Boolean,
        val specialPerks: List<String>
    )

    // MARK: - Core Profile Methods

    /**
     * Get user profile by ID - PRIMARY METHOD for ProfileView
     */
    suspend fun getUserProfile(userID: String): BasicUserInfo? {
        return try {
            _isLoading.value = true
            println("USER SERVICE: Loading profile for user $userID")

            val document = db.collection("users").document(userID).get().await()

            if (document.exists()) {
                val userInfo = BasicUserInfo.fromFirebaseDocument(document)
                println("USER SERVICE: Successfully loaded profile for ${userInfo?.username}")
                userInfo
            } else {
                println("USER SERVICE: User $userID not found")
                null
            }

        } catch (e: Exception) {
            handleError("Failed to get user profile: ${e.message}")
            null
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Get user's videos for ProfileView grid display
     */
    suspend fun getUserVideos(userID: String): List<ProfileVideoMetadata> {
        return try {
            println("USER SERVICE: Loading videos for user $userID")

            val videosSnapshot = db.collection("videos")
                .whereEqualTo("creatorID", userID)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(100)
                .get()
                .await()

            val videos = videosSnapshot.documents.mapNotNull { doc ->
                ProfileVideoMetadata.fromFirebaseDocument(doc)
            }

            println("USER SERVICE: Loaded ${videos.size} videos for user")
            videos

        } catch (e: Exception) {
            println("USER SERVICE: Failed to load user videos: ${e.message}")
            emptyList()
        }
    }

    /**
     * Update user statistics
     */
    suspend fun updateUserStats(userID: String, stats: UserStats) {
        try {
            val updates = mapOf(
                "totalVideos" to stats.totalVideos,
                "totalViews" to stats.totalViews,
                "totalLikes" to stats.totalLikes,
                "averageEngagement" to stats.averageEngagement,
                "weeklyGrowth" to stats.weeklyGrowth,
                "updatedAt" to Timestamp.now()
            )

            db.collection("users").document(userID).update(updates).await()
            println("USER SERVICE: Updated stats for user $userID")

        } catch (e: Exception) {
            handleError("Failed to update user stats: ${e.message}")
        }
    }

    // MARK: - Social Relationship Methods

    /**
     * Get followers count for user
     */
    suspend fun getFollowersCount(userID: String): Int {
        return try {
            val userDoc = db.collection("users").document(userID).get().await()
            (userDoc.data?.get("followerCount") as? Long)?.toInt() ?: 0
        } catch (e: Exception) {
            println("USER SERVICE: Failed to get followers count: ${e.message}")
            0
        }
    }

    /**
     * Get following count for user
     */
    suspend fun getFollowingCount(userID: String): Int {
        return try {
            val userDoc = db.collection("users").document(userID).get().await()
            (userDoc.data?.get("followingCount") as? Long)?.toInt() ?: 0
        } catch (e: Exception) {
            println("USER SERVICE: Failed to get following count: ${e.message}")
            0
        }
    }

    /**
     * Get followers list for ProfileView
     */
    suspend fun getFollowersList(userID: String): List<BasicUserInfo> {
        return try {
            println("USER SERVICE: Loading followers for user $userID")

            val followersSnapshot = db.collection("following")
                .whereEqualTo("followingID", userID)
                .whereEqualTo("isActive", true)
                .limit(100)
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
            println("USER SERVICE: Failed to get followers: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get following list for ProfileView
     */
    suspend fun getFollowingList(userID: String): List<BasicUserInfo> {
        return try {
            println("USER SERVICE: Loading following for user $userID")

            val followingSnapshot = db.collection("following")
                .whereEqualTo("followerID", userID)
                .whereEqualTo("isActive", true)
                .limit(100)
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

            println("USER SERVICE: Loaded ${following.size} following")
            following

        } catch (e: Exception) {
            println("USER SERVICE: Failed to get following: ${e.message}")
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

    /**
     * Follow a user
     */
    suspend fun followUser(followerID: String, followingID: String) {
        try {
            val isCurrentlyFollowing = isFollowing(followerID, followingID)
            if (isCurrentlyFollowing) {
                println("USER SERVICE: Already following $followingID")
                return
            }

            val batch = db.batch()

            // Create follow relationship
            val followDocID = "${followerID}_${followingID}"
            val followRef = db.collection("following").document(followDocID)
            val followData = mapOf(
                "followerID" to followerID,
                "followingID" to followingID,
                "isActive" to true,
                "createdAt" to Timestamp.now()
            )
            batch.set(followRef, followData)

            // Update follower count
            val followingUserRef = db.collection("users").document(followingID)
            batch.update(followingUserRef, "followerCount", FieldValue.increment(1))

            // Update following count
            val followerUserRef = db.collection("users").document(followerID)
            batch.update(followerUserRef, "followingCount", FieldValue.increment(1))

            batch.commit().await()
            println("USER SERVICE: $followerID followed $followingID")

        } catch (e: Exception) {
            handleError("Failed to follow user: ${e.message}")
        }
    }

    /**
     * Unfollow a user
     */
    suspend fun unfollowUser(followerID: String, followingID: String) {
        try {
            val followDocID = "${followerID}_${followingID}"

            val isCurrentlyFollowing = isFollowing(followerID, followingID)
            if (!isCurrentlyFollowing) {
                println("USER SERVICE: Not following $followingID")
                return
            }

            val batch = db.batch()

            // Remove follow relationship
            val followRef = db.collection("following").document(followDocID)
            batch.delete(followRef)

            // Update follower count
            val followingUserRef = db.collection("users").document(followingID)
            batch.update(followingUserRef, "followerCount", FieldValue.increment(-1))

            // Update following count
            val followerUserRef = db.collection("users").document(followerID)
            batch.update(followerUserRef, "followingCount", FieldValue.increment(-1))

            batch.commit().await()
            println("USER SERVICE: $followerID unfollowed $followingID")

        } catch (e: Exception) {
            handleError("Failed to unfollow user: ${e.message}")
        }
    }

    /**
     * Check if user is following another user
     */
    suspend fun isFollowing(followerID: String, followingID: String): Boolean {
        return try {
            val followDocID = "${followerID}_${followingID}"
            val document = db.collection("following").document(followDocID).get().await()

            document.exists() && (document.data?.get("isActive") as? Boolean ?: false)
        } catch (e: Exception) {
            println("USER SERVICE: Failed to check following status: ${e.message}")
            false
        }
    }

    // MARK: - Tier Progression Methods

    /**
     * Calculate user tier based on clout - NON-SUSPEND VERSION
     */
    fun calculateUserTier(clout: Int): UserTier {
        return when (clout) {
            in UserTier.ROOKIE.cloutRange -> UserTier.ROOKIE
            in UserTier.RISING.cloutRange -> UserTier.RISING
            in UserTier.VETERAN.cloutRange -> UserTier.VETERAN
            in UserTier.INFLUENCER.cloutRange -> UserTier.INFLUENCER
            in UserTier.ELITE.cloutRange -> UserTier.ELITE
            in UserTier.PARTNER.cloutRange -> UserTier.PARTNER
            in UserTier.LEGENDARY.cloutRange -> UserTier.LEGENDARY
            else -> if (clout >= UserTier.TOP_CREATOR.cloutRange.first) UserTier.TOP_CREATOR else UserTier.ROOKIE
        }
    }

    /**
     * Update user tier
     */
    suspend fun updateUserTier(userID: String, newTier: UserTier) {
        try {
            val updates = mapOf(
                "tier" to newTier.rawValue,
                "updatedAt" to Timestamp.now()
            )

            db.collection("users").document(userID).update(updates).await()
            println("USER SERVICE: Updated tier for user $userID to ${newTier.displayName}")

        } catch (e: Exception) {
            handleError("Failed to update user tier: ${e.message}")
        }
    }

    /**
     * Get tier progression information
     */
    suspend fun getTierProgress(userID: String): TierProgress? {
        return try {
            val user = getUserProfile(userID) ?: return null
            val currentTier = user.tier
            val currentClout = user.clout

            // Find next achievable tier
            val nextTier = UserTier.values()
                .filter { it.isAchievableTier && it.cloutRange.first > currentClout }
                .minByOrNull { it.cloutRange.first }

            val cloutNeeded = nextTier?.cloutRange?.first?.minus(currentClout) ?: 0
            val progressPercentage = if (nextTier != null) {
                val tierStart = currentTier.cloutRange.first
                val tierEnd = nextTier.cloutRange.first
                val progress = currentClout - tierStart
                val range = tierEnd - tierStart
                if (range > 0) (progress.toDouble() / range.toDouble()) * 100.0 else 100.0
            } else 100.0

            TierProgress(
                currentTier = currentTier,
                currentClout = currentClout,
                nextTier = nextTier,
                cloutNeeded = cloutNeeded,
                progressPercentage = progressPercentage
            )

        } catch (e: Exception) {
            println("USER SERVICE: Failed to get tier progress: ${e.message}")
            null
        }
    }

    // MARK: - User Creation and Management

    /**
     * Create new user with special user detection
     */
    suspend fun createUser(
        id: String,
        email: String,
        displayName: String? = null,
        profileImageURL: String? = null
    ): BasicUserInfo {
        try {
            val username = generateUsername(email, id)
            val now = Timestamp.now()

            // Check if special user
            val isSpecialUser = checkSpecialUserStatus(email)
            val specialEntry = getSpecialUserEntry(email)

            val initialTier = specialEntry?.tier ?: UserTier.ROOKIE
            val initialClout = specialEntry?.startingClout ?: 0

            val userData = mapOf(
                "email" to email,
                "username" to username,
                "displayName" to (specialEntry?.customTitle ?: displayName ?: username),
                "bio" to (specialEntry?.customBio ?: ""),
                "profileImageURL" to (profileImageURL ?: ""),
                "tier" to initialTier.rawValue,
                "clout" to initialClout,
                "followerCount" to 0,
                "followingCount" to 0,
                "videoCount" to 0,
                "isPrivate" to false,
                "isVerified" to isSpecialUser,
                "createdAt" to now,
                "updatedAt" to now,
                "deviceInfo" to mapOf(
                    "appVersion" to "1.5",
                    "deviceModel" to "Android",
                    "platform" to "Android",
                    "systemVersion" to "14"
                ),
                "fcmToken" to ""
            )

            db.collection("users").document(id).set(userData).await()

            if (!isSpecialUser) {
                autoFollowSpecialUsers(id)
            }

            println("USER SERVICE: User created: $id")

            return BasicUserInfo(
                id = id,
                username = username,
                displayName = specialEntry?.customTitle ?: displayName ?: username,
                profileImageURL = profileImageURL,
                bio = specialEntry?.customBio,
                tier = initialTier,
                clout = initialClout,
                isVerified = isSpecialUser,
                isPrivate = false,
                followerCount = 0,
                followingCount = 0,
                videoCount = 0,
                createdAt = Date()
            )

        } catch (e: Exception) {
            val error = StitchError.ProcessingError("Failed to create user: ${e.message}")
            _lastError.value = error
            throw error
        }
    }

    /**
     * Update user profile information
     */
    suspend fun updateProfile(
        userID: String,
        displayName: String? = null,
        bio: String? = null,
        isPrivate: Boolean? = null
    ) {
        try {
            val updates = mutableMapOf<String, Any>(
                "updatedAt" to Timestamp.now()
            )

            displayName?.let { updates["displayName"] = it }
            bio?.let { updates["bio"] = it }
            isPrivate?.let { updates["isPrivate"] = it }

            db.collection("users").document(userID).update(updates).await()
            println("USER SERVICE: Profile updated for user $userID")

        } catch (e: Exception) {
            handleError("Failed to update profile: ${e.message}")
        }
    }

    /**
     * Add clout points to user
     */
    suspend fun addClout(userID: String, points: Int) {
        try {
            val userRef = db.collection("users").document(userID)

            db.runTransaction { transaction ->
                val snapshot = transaction.get(userRef)
                val currentClout = snapshot.getLong("clout")?.toInt() ?: 0
                val newClout = currentClout + points

                // Calculate new tier
                val newTier = calculateUserTier(newClout)
                val currentTierString = snapshot.getString("tier") ?: "rookie"
                val currentTier = UserTier.valueOf(currentTierString.uppercase())

                transaction.update(userRef, "clout", newClout)
                transaction.update(userRef, "updatedAt", Timestamp.now())

                // Update tier if changed
                if (newTier != currentTier) {
                    transaction.update(userRef, "tier", newTier.rawValue)
                    println("USER SERVICE: Tier upgraded from ${currentTier.displayName} to ${newTier.displayName}")
                }

                null
            }.await()

            println("USER SERVICE: Added $points clout to user $userID")

        } catch (e: Exception) {
            handleError("Failed to add clout: ${e.message}")
        }
    }

    // MARK: - Helper Methods

    private fun generateUsername(email: String, id: String): String {
        return if (email.isNotEmpty()) {
            val emailPrefix = email.split("@").firstOrNull() ?: "user"
            "${emailPrefix}_${id.take(6)}"
        } else {
            "user_${id.take(8)}"
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

    private fun getSpecialUserEntry(email: String): SpecialUserEntry? {
        val specialUsers = mapOf(
            "founder@stitchsocial.me" to SpecialUserEntry(
                email = "founder@stitchsocial.me",
                startingClout = 50000,
                tier = UserTier.FOUNDER,
                customTitle = "Founder",
                customBio = "Founder of Stitch Social | Building the future of social video",
                isAutoFollowed = true,
                specialPerks = listOf("unlimited_uploads", "custom_badges", "priority_support")
            ),
            "teddyruks@gmail.com" to SpecialUserEntry(
                email = "teddyruks@gmail.com",
                startingClout = 25000,
                tier = UserTier.CO_FOUNDER,
                customTitle = "Co-Founder",
                customBio = "Co-Founder of Stitch Social | Video creator and tech enthusiast",
                isAutoFollowed = true,
                specialPerks = listOf("unlimited_uploads", "custom_badges")
            )
        )
        return specialUsers[email.lowercase()]
    }

    private suspend fun autoFollowSpecialUsers(userID: String) {
        val autoFollowEmails = listOf("founder@stitchsocial.me", "teddyruks@gmail.com")

        for (email in autoFollowEmails) {
            try {
                // Find special user by email and auto-follow
                val specialUserQuery = db.collection("users")
                    .whereEqualTo("email", email)
                    .limit(1)
                    .get()
                    .await()

                if (!specialUserQuery.isEmpty) {
                    val specialUserID = specialUserQuery.documents.first().id
                    followUser(userID, specialUserID)
                    println("USER SERVICE: Auto-followed special user $email")
                }
            } catch (e: Exception) {
                println("USER SERVICE: Failed to auto-follow $email: ${e.message}")
            }
        }
    }

    private fun handleError(message: String) {
        val error = StitchError.NetworkError(message)
        _lastError.value = error
        println("❌ USER SERVICE: $message")
    }

    fun clearError() {
        _lastError.value = null
    }

    fun helloWorldTest() {
        println("USER SERVICE: Hello World - Complete user management ready!")
        println("USER SERVICE: Features: Profile data, social relationships, tier progression")
        println("USER SERVICE: Integration: ProfileView, HomeFeedView, AuthService")
    }
}