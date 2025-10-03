package com.stitchsocial.foundation

import java.util.Date

/**
 * Complete user profile data model for Stitch Social
 * Layer 1: Foundation - Pure Kotlin data class with no Android dependencies
 *
 * Provides comprehensive user state including social metrics, engagement stats,
 * and all data needed for profile management, social features, and UI display.
 *
 * Design principles:
 * - Immutable data class with val properties
 * - Copy-with-update methods for state changes
 * - Computed properties for derived values
 * - Validation logic for UI and business rules
 * - Firebase serialization ready
 */
data class UserProfileData(
    // ===== CORE IDENTITY =====
    val id: String,
    val username: String,
    val displayName: String,
    val email: String,
    val bio: String,
    val profileImageURL: String?,

    // ===== USER CLASSIFICATION =====
    val tier: UserTier,
    val clout: Int,
    val isVerified: Boolean,

    // ===== SOCIAL METRICS (CRITICAL) =====
    val followerCount: Int,
    val followingCount: Int,
    val videoCount: Int,
    val threadCount: Int,
    val totalHypesReceived: Int,
    val totalCoolsReceived: Int,

    // ===== PRIVACY & STATUS =====
    val isPrivate: Boolean,
    val lastActiveAt: Date,
    val createdAt: Date
) {

    // ===== COMPUTED PROPERTIES =====

    /**
     * User engagement ratio based on total interactions vs content created
     * Returns 0.0 if no videos created yet
     */
    val engagementRatio: Double
        get() = if (videoCount > 0) {
            (totalHypesReceived + totalCoolsReceived).toDouble() / videoCount.toDouble()
        } else {
            0.0
        }

    /**
     * Social influence score combining followers, engagement, and tier
     * Range: 0.0 to 100.0+
     */
    val socialScore: Double
        get() {
            val followerWeight = followerCount * 1.0
            val engagementWeight = engagementRatio * 10.0
            val tierMultiplier = when (tier) {
                UserTier.ROOKIE -> 1.0
                UserTier.RISING -> 1.2
                UserTier.CREATOR -> 1.5
                UserTier.INFLUENCER -> 2.0
                UserTier.LEGEND -> 3.0
            }
            val verificationBonus = if (isVerified) 10.0 else 0.0

            return ((followerWeight + engagementWeight) * tierMultiplier) + verificationBonus
        }

    /**
     * Formatted username with @ symbol for display
     */
    val displayUsername: String
        get() = "@$username"

    /**
     * Whether user has been active recently (within last 7 days)
     */
    val isActiveUser: Boolean
        get() {
            val sevenDaysAgo = Date(System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000))
            return lastActiveAt.after(sevenDaysAgo)
        }

    /**
     * Whether user has any content created
     */
    val hasContent: Boolean
        get() = videoCount > 0 || threadCount > 0

    /**
     * Total content pieces created
     */
    val totalContentCount: Int
        get() = videoCount + threadCount

    // ===== VALIDATION PROPERTIES =====

    /**
     * Username validation (3-20 chars, alphanumeric + underscore)
     */
    val isUsernameValid: Boolean
        get() = username.length in 3..20 && username.matches(Regex("^[a-zA-Z0-9_]+$"))

    /**
     * Bio validation (max 150 characters)
     */
    val isBioValid: Boolean
        get() = bio.length <= 150

    /**
     * Display name validation (1-30 characters, not empty)
     */
    val isDisplayNameValid: Boolean
        get() = displayName.isNotBlank() && displayName.length <= 30

    /**
     * Email validation (basic format check)
     */
    val isEmailValid: Boolean
        get() = email.contains("@") && email.contains(".") && email.length >= 5

    /**
     * Whether user can create videos (not banned/restricted)
     */
    val canCreateVideos: Boolean
        get() = tier != UserTier.ROOKIE || videoCount < 5 // Rookies limited to 5 videos

    /**
     * Whether user can receive new follows (not private or at limit)
     */
    val canReceiveFollows: Boolean
        get() = !isPrivate || followerCount < 10000 // Private accounts limited to 10k followers

    /**
     * Whether user profile is complete enough for discovery
     */
    val isProfileComplete: Boolean
        get() = isUsernameValid && isBioValid && isDisplayNameValid &&
                profileImageURL != null && bio.isNotBlank()

    // ===== IMMUTABLE UPDATE METHODS =====

    /**
     * Update bio with validation
     */
    fun withUpdatedBio(newBio: String): UserProfileData {
        require(newBio.length <= 150) { "Bio cannot exceed 150 characters" }
        return copy(bio = newBio)
    }

    /**
     * Update display name with validation
     */
    fun withUpdatedDisplayName(newName: String): UserProfileData {
        require(newName.isNotBlank() && newName.length <= 30) { "Display name must be 1-30 characters" }
        return copy(displayName = newName)
    }

    /**
     * Update username with validation
     */
    fun withUpdatedUsername(newUsername: String): UserProfileData {
        require(newUsername.length in 3..20) { "Username must be 3-20 characters" }
        require(newUsername.matches(Regex("^[a-zA-Z0-9_]+$"))) { "Username can only contain letters, numbers, and underscores" }
        return copy(username = newUsername)
    }

    /**
     * Update privacy setting
     */
    fun withUpdatedPrivacy(newPrivacy: Boolean): UserProfileData {
        return copy(isPrivate = newPrivacy)
    }

    /**
     * Update profile image URL
     */
    fun withUpdatedProfileImage(newImageURL: String?): UserProfileData {
        return copy(profileImageURL = newImageURL)
    }

    /**
     * Update email with validation
     */
    fun withUpdatedEmail(newEmail: String): UserProfileData {
        require(newEmail.contains("@") && newEmail.contains(".")) { "Invalid email format" }
        return copy(email = newEmail)
    }

    // ===== SOCIAL STAT UPDATES =====

    /**
     * Increment follower count
     */
    fun withIncrementedFollowers(): UserProfileData {
        return copy(followerCount = followerCount + 1)
    }

    /**
     * Decrement follower count (cannot go below 0)
     */
    fun withDecrementedFollowers(): UserProfileData {
        return copy(followerCount = maxOf(0, followerCount - 1))
    }

    /**
     * Increment following count
     */
    fun withIncrementedFollowing(): UserProfileData {
        return copy(followingCount = followingCount + 1)
    }

    /**
     * Decrement following count (cannot go below 0)
     */
    fun withDecrementedFollowing(): UserProfileData {
        return copy(followingCount = maxOf(0, followingCount - 1))
    }

    /**
     * Increment video count
     */
    fun withIncrementedVideos(): UserProfileData {
        return copy(videoCount = videoCount + 1)
    }

    /**
     * Increment thread count
     */
    fun withIncrementedThreads(): UserProfileData {
        return copy(threadCount = threadCount + 1)
    }

    /**
     * Add hype engagement
     */
    fun withAddedHypes(count: Int = 1): UserProfileData {
        require(count >= 0) { "Hype count cannot be negative" }
        return copy(totalHypesReceived = totalHypesReceived + count)
    }

    /**
     * Add cool engagement
     */
    fun withAddedCools(count: Int = 1): UserProfileData {
        require(count >= 0) { "Cool count cannot be negative" }
        return copy(totalCoolsReceived = totalCoolsReceived + count)
    }

    /**
     * Update clout score
     */
    fun withUpdatedClout(newClout: Int): UserProfileData {
        require(newClout >= 0) { "Clout cannot be negative" }
        return copy(clout = newClout)
    }

    /**
     * Update user tier
     */
    fun withUpdatedTier(newTier: UserTier): UserProfileData {
        return copy(tier = newTier)
    }

    /**
     * Update verification status
     */
    fun withUpdatedVerification(verified: Boolean): UserProfileData {
        return copy(isVerified = verified)
    }

    /**
     * Update last active timestamp to now
     */
    fun withUpdatedLastActive(): UserProfileData {
        return copy(lastActiveAt = Date())
    }

    // ===== BATCH UPDATES =====

    /**
     * Update multiple social stats at once (for engagement events)
     */
    fun withUpdatedEngagementStats(
        newHypes: Int? = null,
        newCools: Int? = null,
        newFollowers: Int? = null
    ): UserProfileData {
        return copy(
            totalHypesReceived = newHypes ?: totalHypesReceived,
            totalCoolsReceived = newCools ?: totalCoolsReceived,
            followerCount = newFollowers ?: followerCount,
            lastActiveAt = Date()
        )
    }

    /**
     * Update profile information in batch
     */
    fun withUpdatedProfileInfo(
        newDisplayName: String? = null,
        newBio: String? = null,
        newProfileImage: String? = null,
        newPrivacy: Boolean? = null
    ): UserProfileData {
        val updatedDisplayName = newDisplayName?.let {
            require(it.isNotBlank() && it.length <= 30) { "Invalid display name" }
            it
        } ?: displayName

        val updatedBio = newBio?.let {
            require(it.length <= 150) { "Bio too long" }
            it
        } ?: bio

        return copy(
            displayName = updatedDisplayName,
            bio = updatedBio,
            profileImageURL = newProfileImage ?: profileImageURL,
            isPrivate = newPrivacy ?: isPrivate,
            lastActiveAt = Date()
        )
    }

    // ===== FACTORY METHODS =====

    companion object {
        /**
         * Create new user profile with minimal required data
         */
        fun newUser(
            id: String,
            username: String,
            displayName: String,
            email: String
        ): UserProfileData {
            val now = Date()
            return UserProfileData(
                id = id,
                username = username,
                displayName = displayName,
                email = email,
                bio = "",
                profileImageURL = null,
                tier = UserTier.ROOKIE,
                clout = 0,
                isVerified = false,
                followerCount = 0,
                followingCount = 0,
                videoCount = 0,
                threadCount = 0,
                totalHypesReceived = 0,
                totalCoolsReceived = 0,
                isPrivate = false,
                lastActiveAt = now,
                createdAt = now
            )
        }

        /**
         * Create test user for development/testing
         */
        fun testUser(
            id: String = "test_user_123",
            username: String = "testuser",
            followers: Int = 100,
            videos: Int = 5
        ): UserProfileData {
            return newUser(id, username, "Test User", "test@example.com")
                .copy(
                    bio = "This is a test user account",
                    followerCount = followers,
                    videoCount = videos,
                    totalHypesReceived = videos * 10,
                    totalCoolsReceived = videos * 2,
                    tier = UserTier.RISING
                )
        }
    }
}

/**
 * User tier enumeration representing user status/permissions
 */
enum class UserTier {
    ROOKIE,      // New users, limited features
    RISING,      // Active users gaining traction  
    CREATOR,     // Established content creators
    INFLUENCER,  // High-engagement users
    LEGEND       // Top-tier users with maximum privileges
}