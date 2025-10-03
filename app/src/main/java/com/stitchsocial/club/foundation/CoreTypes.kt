package com.stitchsocial.club.foundation

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import java.util.Date

/**
 * CoreTypes.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Foundation Layer - Zero dependencies
 * UPDATED: Added bio field to BasicUserInfo and fixed Firebase parsing
 * Handles: badges array, totalLikes, followerCount, BIO FIELD
 *
 * Layer 1: Foundation - No dependencies (Kotlin stdlib only)
 */

// MARK: - User System Types

/**
 * User tier system based on clout points
 * Exact translation from Swift UserTier enum
 */
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

    val crownBadge: String?
        get() = when (this) {
            FOUNDER -> "founder_crown"
            CO_FOUNDER -> "cofounder_crown"
            TOP_CREATOR -> "creator_crown"
            LEGENDARY -> "legendary_crown"
            else -> null
        }
}

/**
 * Content type hierarchy - Thread → Child → Stepchild
 * Exact translation from Swift ContentType enum
 */
enum class ContentType(val rawValue: String) {
    THREAD("thread"),
    CHILD("child"),
    STEPCHILD("stepchild");

    val displayName: String
        get() = when (this) {
            THREAD -> "Thread"
            CHILD -> "Child"
            STEPCHILD -> "Stepchild"
        }
}

/**
 * Video temperature system (like Reddit hot/rising)
 * Exact translation from Swift Temperature enum
 */
enum class Temperature(val rawValue: String) {
    FROZEN("frozen"),
    COLD("cold"),
    COOL("cool"),
    WARM("warm"),
    HOT("hot"),
    BLAZING("blazing");

    val displayName: String
        get() = when (this) {
            FROZEN -> "Frozen"
            COLD -> "Cold"
            COOL -> "Cool"
            WARM -> "Warm"
            HOT -> "Hot"
            BLAZING -> "Blazing"
        }

    val threshold: Double
        get() = when (this) {
            FROZEN -> 0.0
            COLD -> 0.1
            COOL -> 0.3
            WARM -> 0.6
            HOT -> 0.8
            BLAZING -> 0.95
        }
}

// MARK: - Interaction and Engagement Types

/**
 * Interaction types for engagement system
 * Referenced by VideoService.kt and EngagementCoordinator.kt
 */
enum class InteractionType(val displayName: String) {
    HYPE("Hype"),
    COOL("Cool"),
    VIEW("View"),
    SHARE("Share"),
    REPLY("Reply");

    val emoji: String
        get() = when (this) {
            HYPE -> "🔥"
            COOL -> "❄️"
            VIEW -> "👁️"
            SHARE -> "📤"
            REPLY -> "💬"
        }
}

/**
 * Video analysis result from AI processing
 * Referenced by CameraViewModel.kt and VideoCoordinator.kt
 */
data class VideoAnalysisResult(
    val transcript: String,
    val contentQuality: Double,
    val processingTime: Double,
    val confidence: Double,
    val suggestedTitle: String = "",
    val suggestedDescription: String = "",
    val suggestedHashtags: List<String> = emptyList(),
    val contentFlags: List<String> = emptyList()
)

/**
 * Camera and recording error types
 * Referenced by CameraViewModel.kt
 */
enum class CameraError(val displayName: String) {
    PermissionDenied("Camera permission denied"),
    InvalidVideo("Invalid video format"),
    RecordingFailed("Recording failed"),
    ProcessingFailed("Video processing failed"),
    StorageFull("Insufficient storage"),
    Unknown("Unknown camera error");

    val userMessage: String
        get() = when (this) {
            PermissionDenied -> "Please grant camera permission to record videos"
            InvalidVideo -> "Video format not supported"
            RecordingFailed -> "Failed to record video. Please try again"
            ProcessingFailed -> "Failed to process video. Please try again"
            StorageFull -> "Not enough storage space available"
            Unknown -> "Something went wrong. Please try again"
        }
}

// MARK: - Authentication Types

/**
 * Authentication state management
 * Exact translation from Swift AuthState enum
 */
enum class AuthState(val rawValue: String) {
    SIGNED_OUT("signed_out"),
    SIGNING_IN("signing_in"),
    SIGNED_IN("signed_in"),
    SIGNING_OUT("signing_out"),
    ERROR("error");

    val displayName: String
        get() = when (this) {
            SIGNED_OUT -> "Sign In"
            SIGNED_IN -> "Signed In"
            SIGNING_IN -> "Signing In..."
            SIGNING_OUT -> "Signing Out..."
            ERROR -> "Sign In Error"
        }
}

/**
 * Recording states
 * Exact translation from Swift RecordingState enum
 */
enum class RecordingState(val rawValue: String) {
    IDLE("idle"),
    RECORDING("recording"),
    PAUSED("paused"),
    PROCESSING("processing"),
    COMPLETE("complete"),
    ERROR("error");

    val displayName: String
        get() = when (this) {
            IDLE -> "Ready"
            RECORDING -> "Recording"
            PAUSED -> "Paused"
            PROCESSING -> "Processing"
            COMPLETE -> "Complete"
            ERROR -> "Error"
        }
}

// MARK: - Cache System Types

/**
 * Cache types for different content
 * Exact translation from Swift CacheType enum
 */
enum class CacheType {
    VIDEO,
    IMAGE,
    DATA,
    THUMBNAIL,
    UPLOAD_RESULT,
    USER_PROFILE,
    THREAD_DATA,
    ENGAGEMENT;

    val prefix: String
        get() = when (this) {
            VIDEO -> "vid"
            IMAGE -> "img"
            DATA -> "dat"
            THUMBNAIL -> "tmb"
            UPLOAD_RESULT -> "upl"
            USER_PROFILE -> "usr"
            THREAD_DATA -> "thd"
            ENGAGEMENT -> "eng"
        }
}

// MARK: - Error Types

/**
 * App-wide error types
 * Exact translation from Swift StitchError enum
 */
sealed class StitchError(override val message: String) : Exception(message) {
    class NetworkError(message: String) : StitchError("Network Error: $message")
    class AuthenticationError(message: String) : StitchError("Authentication Error: $message")
    class ValidationError(message: String) : StitchError("Validation Error: $message")
    class StorageError(message: String) : StitchError("Storage Error: $message")
    class RecordingError(message: String) : StitchError("Recording Error: $message")
    class ProcessingError(message: String) : StitchError("Processing Error: $message")
    object UnknownError : StitchError("An unknown error occurred")
}

// MARK: - Basic Data Structures

/**
 * Complete user information with BIO FIELD ADDED and PHASE 1 ENHANCEMENTS
 * Handles: badges array, totalLikes, followerCount, BIO field for profile editing
 * ENHANCED: Added missing social fields for Phase 1 core functionality
 */
data class BasicUserInfo(
    val id: String,
    val username: String,
    val displayName: String,
    val email: String,
    val tier: UserTier,
    val clout: Int,
    val isVerified: Boolean = false,
    val isPrivate: Boolean = false,
    val profileImageURL: String? = null,
    val bio: String = "", // FIXED: Added bio field for profile editing
    val badges: List<String> = emptyList(),
    val followerCount: Int = 0,
    val followingCount: Int = 0,
    val totalLikes: Int = 0,
    val totalVideos: Int = 0,
    val totalViews: Int = 0,
    val videoCount: Int = 0,

    // ===== PHASE 1 CRITICAL ADDITIONS =====
    val threadCount: Int = 0, // Thread hierarchy support
    val totalHypesReceived: Int = 0, // Engagement tracking
    val totalCoolsReceived: Int = 0, // Engagement tracking

    val createdAt: Date = Date(),
    val lastActiveAt: Date = Date()
) {

    // ===== PHASE 1 COMPUTED PROPERTIES =====

    /**
     * Engagement ratio for algorithm scoring
     */
    val engagementRatio: Double
        get() = if (videoCount > 0) {
            (totalHypesReceived + totalCoolsReceived).toDouble() / videoCount.toDouble()
        } else {
            0.0
        }

    /**
     * Social influence score
     */
    val socialScore: Double
        get() = (followerCount * 1.0) + (totalHypesReceived * 0.5) + (totalViews * 0.1)

    /**
     * Display username with @ symbol
     */
    val displayUsername: String
        get() = "@$username"

    /**
     * Whether user has early adopter badge
     */
    val hasEarlyAdopterBadge: Boolean
        get() = badges.contains("early_adopter")

    // ===== PHASE 1 UPDATE METHODS =====

    /**
     * Create updated profile with new display name
     */
    fun withUpdatedDisplayName(newDisplayName: String): BasicUserInfo {
        return copy(displayName = newDisplayName)
    }

    /**
     * Create updated profile with new bio
     */
    fun withUpdatedBio(newBio: String): BasicUserInfo {
        return copy(bio = newBio)
    }

    /**
     * Create updated profile with new username
     */
    fun withUpdatedUsername(newUsername: String): BasicUserInfo {
        return copy(username = newUsername)
    }

    /**
     * Create updated profile with new privacy setting
     */
    fun withUpdatedPrivacy(isPrivate: Boolean): BasicUserInfo {
        return copy(isPrivate = isPrivate)
    }

    /**
     * Create updated profile with new profile image
     */
    fun withUpdatedProfileImage(imageURL: String?): BasicUserInfo {
        return copy(profileImageURL = imageURL)
    }

    companion object {
        /**
         * Parse BasicUserInfo from Firebase document - UPDATED to read bio field and Phase 1 fields
         * Handles all your Firebase field names including BIO and social metrics
         */
        fun fromFirebaseDocument(doc: DocumentSnapshot): BasicUserInfo? {
            return try {
                val data = doc.data ?: return null

                println("BASIC USER INFO: 📄 Parsing Firebase document...")
                println("BASIC USER INFO: Document ID: ${doc.id}")
                println("BASIC USER INFO: Total fields: ${data.keys.size}")

                // Extract all fields matching your Firebase structure EXACTLY
                val username = data["username"] as? String ?: ""
                val displayName = data["displayName"] as? String ?: ""
                val email = data["email"] as? String ?: ""
                val tierString = data["tier"] as? String ?: "rookie"
                val bio = data["bio"] as? String ?: "" // FIXED: Actually read bio from Firebase
                val profileImageURL = data["profileImageURL"] as? String
                val isVerified = data["isVerified"] as? Boolean ?: false
                val isPrivate = data["isPrivate"] as? Boolean ?: false

                // Handle numeric fields (can be Long, Int, or Double from Firebase)
                val clout = when (val value = data["clout"]) {
                    is Long -> value.toInt()
                    is Int -> value
                    is Double -> value.toInt()
                    else -> 0
                }

                val followerCount = when (val value = data["followerCount"]) {
                    is Long -> value.toInt()
                    is Int -> value
                    is Double -> value.toInt()
                    else -> 0
                }

                val followingCount = when (val value = data["followingCount"]) {
                    is Long -> value.toInt()
                    is Int -> value
                    is Double -> value.toInt()
                    else -> 0
                }

                val totalLikes = when (val value = data["totalLikes"]) {
                    is Long -> value.toInt()
                    is Int -> value
                    is Double -> value.toInt()
                    else -> 0
                }

                val totalVideos = when (val value = data["totalVideos"]) {
                    is Long -> value.toInt()
                    is Int -> value
                    is Double -> value.toInt()
                    else -> 0
                }

                val totalViews = when (val value = data["totalViews"]) {
                    is Long -> value.toInt()
                    is Int -> value
                    is Double -> value.toInt()
                    else -> 0
                }

                val videoCount = when (val value = data["videoCount"]) {
                    is Long -> value.toInt()
                    is Int -> value
                    is Double -> value.toInt()
                    else -> 0
                }

                // ===== PHASE 1 NEW FIELDS =====
                val threadCount = when (val value = data["threadCount"]) {
                    is Long -> value.toInt()
                    is Int -> value
                    is Double -> value.toInt()
                    else -> 0
                }

                val totalHypesReceived = when (val value = data["totalHypesReceived"]) {
                    is Long -> value.toInt()
                    is Int -> value
                    is Double -> value.toInt()
                    else -> 0
                }

                val totalCoolsReceived = when (val value = data["totalCoolsReceived"]) {
                    is Long -> value.toInt()
                    is Int -> value
                    is Double -> value.toInt()
                    else -> 0
                }

                // Handle badges array
                val badges = when (val badgesValue = data["badges"]) {
                    is List<*> -> badgesValue.filterIsInstance<String>()
                    else -> emptyList()
                }

                // Handle timestamps
                val createdAt = when (val value = data["createdAt"]) {
                    is Timestamp -> value.toDate()
                    is Long -> Date(value)
                    else -> Date()
                }

                val lastActiveAt = when (val value = data["lastActiveAt"]) {
                    is Timestamp -> value.toDate()
                    is Long -> Date(value)
                    else -> Date()
                }

                // Parse tier with fallback
                val tier = UserTier.values().find { it.rawValue == tierString } ?: UserTier.ROOKIE

                // Validate critical fields
                if (username.isBlank()) {
                    println("BASIC USER INFO: ❌ Username is blank")
                    return null
                }

                if (displayName.isBlank()) {
                    println("BASIC USER INFO: ❌ Display name is blank")
                    return null
                }

                println("BASIC USER INFO: ✅ Successfully parsed user:")
                println("  - Username: @$username")
                println("  - Display Name: $displayName")
                println("  - Email: $email")
                println("  - Bio: '$bio'") // FIXED: Log bio
                println("  - Tier: ${tier.displayName}")
                println("  - Clout: $clout")
                println("  - Verified: $isVerified")
                println("  - Badges: ${badges.joinToString(", ")}")
                println("  - Followers: $followerCount")
                println("  - Following: $followingCount")
                println("  - Total Hypes: $totalHypesReceived")
                println("  - Total Cools: $totalCoolsReceived")
                println("  - Thread Count: $threadCount")

                BasicUserInfo(
                    id = doc.id,
                    username = username,
                    displayName = displayName,
                    email = email,
                    tier = tier,
                    clout = clout,
                    isVerified = isVerified,
                    isPrivate = isPrivate,
                    profileImageURL = profileImageURL,
                    bio = bio, // FIXED: Include bio in result
                    badges = badges,
                    followerCount = followerCount,
                    followingCount = followingCount,
                    totalLikes = totalLikes,
                    totalVideos = totalVideos,
                    totalViews = totalViews,
                    videoCount = videoCount,
                    threadCount = threadCount, // PHASE 1: Added
                    totalHypesReceived = totalHypesReceived, // PHASE 1: Added
                    totalCoolsReceived = totalCoolsReceived, // PHASE 1: Added
                    createdAt = createdAt,
                    lastActiveAt = lastActiveAt
                )
            } catch (e: Exception) {
                println("BASIC USER INFO: ❌ Failed to parse user document ${doc.id}")
                println("BASIC USER INFO: Exception: ${e.javaClass.simpleName}")
                println("BASIC USER INFO: Message: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }
}

/**
 * Basic video information
 * Exact translation from Swift BasicVideoInfo struct
 */
data class BasicVideoInfo(
    val id: String,
    val title: String,
    val videoURL: String,
    val thumbnailURL: String,
    val duration: Double, // TimeInterval in Swift -> Double in Kotlin
    val createdAt: Date,
    val contentType: ContentType,
    val temperature: Temperature
)

/**
 * Engagement metrics
 * Exact translation from Swift EngagementMetrics struct
 */
data class EngagementMetrics(
    val hypeCount: Int,
    val coolCount: Int,
    val replyCount: Int,
    val shareCount: Int,
    val viewCount: Int
) {
    val netScore: Int
        get() = hypeCount - coolCount

    val engagementRatio: Double
        get() {
            val total = hypeCount + coolCount
            return if (total > 0) hypeCount.toDouble() / total.toDouble() else 0.0
        }
}

/**
 * User statistics for tier calculation and badge awards
 * Exact translation from Swift RealUserStats struct
 */
data class RealUserStats(
    val followers: Int = 0,
    val hypes: Int = 0,
    val threads: Int = 0,
    val posts: Int = 0,
    val engagementRate: Double = 0.0,
    val clout: Int = 0
)

// MARK: - Creation Mode Types

/**
 * Creation flow modes
 * Exact translation from Swift CreationMode enum
 */
sealed class CreationMode {
    object NewThread : CreationMode()
    data class ReplyToThread(val threadId: String) : CreationMode()
    data class RespondToChild(val childId: String, val threadId: String) : CreationMode()

    val displayTitle: String
        get() = when (this) {
            is NewThread -> "New Thread"
            is ReplyToThread -> "Reply to Thread"
            is RespondToChild -> "Respond to Child"
        }

    val contentType: ContentType
        get() = when (this) {
            is NewThread -> ContentType.THREAD
            is ReplyToThread -> ContentType.CHILD
            is RespondToChild -> ContentType.STEPCHILD
        }
}

// MARK: - Validation Types

/**
 * Validation result for user input
 */
data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList()
) {
    companion object {
        fun success() = ValidationResult(true)
        fun failure(vararg errors: String) = ValidationResult(false, errors.toList())
    }
}

// MARK: - Additional Foundation Types for Compilation

/**
 * Engagement reward types
 * Referenced by EngagementCoordinator.kt
 */
enum class EngagementRewardType(val displayName: String) {
    CLOUT_GAIN("Clout Gained"),
    MILESTONE_REACHED("Milestone Reached"),
    TIER_PROMOTION("Tier Promotion"),
    BADGE_EARNED("Badge Earned"),
    STREAK_BONUS("Streak Bonus");
}

/**
 * Video creation phases
 * Referenced by VideoCoordinator.kt
 */
enum class VideoCreationPhase(val displayName: String) {
    READY("Ready"),
    RECORDING("Recording"),
    ANALYZING("Analyzing"),
    COMPRESSING("Compressing"),
    UPLOADING("Uploading"),
    CREATING_RECORD("Creating Record"),
    COMPLETE("Complete"),
    ERROR("Error");
}

/**
 * Processing result for video creation workflow
 * Referenced by CameraViewModel.kt
 */
data class ProcessingResult<T>(
    val success: Boolean,
    val data: T? = null,
    val error: StitchError? = null
) {
    companion object {
        fun <T> success(data: T) = ProcessingResult(true, data, null)
        fun <T> failure(error: StitchError) = ProcessingResult<T>(false, null, error)
    }
}