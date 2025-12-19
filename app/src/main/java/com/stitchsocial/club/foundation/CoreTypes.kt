package com.stitchsocial.club.foundation

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import java.util.Date

/**
 * CoreTypes.kt - COMPLETE CONSOLIDATED VERSION
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Foundation Layer - All core type definitions in one place
 * Layer 1: Foundation - No dependencies (Kotlin stdlib only)
 */

// MARK: - Content Type System

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

enum class Temperature(val rawValue: String) {
    FROZEN("frozen"),
    COLD("cold"),
    COOL("cool"),
    WARM("warm"),
    HOT("hot"),
    BLAZING("blazing");

    val displayName: String
        get() = when (this) {
            FROZEN -> "Frozen ❄️"
            COLD -> "Cold 🧊"
            COOL -> "Cool 😎"
            WARM -> "Warm 🌡️"
            HOT -> "Hot 🔥"
            BLAZING -> "Blazing 🚀"
        }

    val emoji: String
        get() = when (this) {
            FROZEN -> "❄️"
            COLD -> "🧊"
            COOL -> "😎"
            WARM -> "🌡️"
            HOT -> "🔥"
            BLAZING -> "🚀"
        }
}

// MARK: - Interaction Types

/**
 * SINGLE SOURCE OF TRUTH for InteractionType
 * All other files MUST import this
 */
enum class InteractionType(val displayName: String) {
    HYPE("Hype"),
    COOL("Cool"),
    VIEW("View"),
    SHARE("Share"),
    REPLY("Reply");

    val pointValue: Int
        get() = when (this) {
            HYPE -> 10
            COOL -> -5
            REPLY -> 50
            SHARE -> 25
            VIEW -> 1
        }

    val emoji: String
        get() = when (this) {
            HYPE -> "🔥"
            COOL -> "❄️"
            VIEW -> "👁️"
            SHARE -> "🔤"
            REPLY -> "💬"
        }
}

/**
 * SINGLE SOURCE OF TRUTH for TapMilestone
 * All other files MUST import this
 */
enum class TapMilestone {
    QUARTER,
    HALF,
    THREE_QUARTERS,
    COMPLETE;

    val displayName: String
        get() = when (this) {
            QUARTER -> "Keep Going!"
            HALF -> "Halfway There!"
            THREE_QUARTERS -> "Almost Done!"
            COMPLETE -> "Complete!"
        }

    val progressValue: Double
        get() = when (this) {
            QUARTER -> 0.25
            HALF -> 0.5
            THREE_QUARTERS -> 0.75
            COMPLETE -> 1.0
        }
}

// MARK: - Upload Types

sealed class UploadError(override val message: String) : Exception(message) {
    object Cancelled : UploadError("Upload cancelled by user")
    object NetworkTimeout : UploadError("Network timeout. Please try again")
    class InvalidFormat(format: String) : UploadError("Invalid format: $format")
    object FileTooLarge : UploadError("File too large. Maximum size is 500MB")
    object NoInternet : UploadError("No internet connection. Please try again")
    object ProcessingFailed : UploadError("Failed to process video. Please try again")
    object StorageFull : UploadError("Not enough storage space available")
    object Unknown : UploadError("Something went wrong. Please try again")

    val userMessage: String
        get() = when (this) {
            is Cancelled -> "Upload cancelled"
            is NetworkTimeout -> "Connection timed out. Please try again"
            is InvalidFormat -> "Invalid video format"
            is FileTooLarge -> "Video file is too large"
            is NoInternet -> "No internet connection"
            is ProcessingFailed -> "Failed to process video. Please try again"
            is StorageFull -> "Not enough storage space available"
            is Unknown -> "Something went wrong. Please try again"
        }
}

// MARK: - Authentication Types

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

data class BasicVideoInfo(
    val id: String,
    val title: String,
    val creatorName: String,
    val creatorID: String,
    val thumbnailURL: String?,
    val videoURL: String,
    val duration: Double,
    val hypeCount: Int = 0,
    val coolCount: Int = 0,
    val viewCount: Int = 0,
    val createdAt: Date = Date(),
    val contentType: ContentType = ContentType.THREAD,
    val temperature: Temperature = Temperature.COOL
)

data class RealUserStats(
    val posts: Int,
    val threads: Int,
    val hypes: Int,
    val followers: Int,
    val engagementRate: Double,
    val clout: Int
)

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
    val bio: String = "",
    val badges: List<String> = emptyList(),
    val followerCount: Int = 0,
    val followingCount: Int = 0,
    val videoCount: Int = 0,
    val threadCount: Int = 0,
    val totalHypesReceived: Int = 0,
    val totalCoolsReceived: Int = 0,
    val totalViews: Int = 0,
    val totalLikes: Int = 0,
    val totalVideos: Int = 0,
    val createdAt: Date = Date(),
    val lastActiveAt: Date = Date()
) {
    val displayUsername: String
        get() = "@$username"

    val hasBadges: Boolean
        get() = badges.isNotEmpty()

    val isActive: Boolean
        get() {
            val sevenDaysAgo = Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000)
            return lastActiveAt.after(sevenDaysAgo)
        }

    companion object {
        fun fromFirebaseDocument(doc: DocumentSnapshot): BasicUserInfo? {
            return try {
                val data = doc.data ?: return null

                val username = data["username"] as? String ?: return null
                val displayName = data["displayName"] as? String ?: username
                val email = data["email"] as? String ?: ""
                val bio = data["bio"] as? String ?: ""
                val tierString = data["tier"] as? String ?: "rookie"
                val isVerified = data["isVerified"] as? Boolean ?: false
                val isPrivate = data["isPrivate"] as? Boolean ?: false
                val profileImageURL = data["profileImageURL"] as? String

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

                val badges = when (val badgesValue = data["badges"]) {
                    is List<*> -> badgesValue.filterIsInstance<String>()
                    else -> emptyList()
                }

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

                val tier = UserTier.fromRawValue(tierString) ?: UserTier.ROOKIE

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
                    bio = bio,
                    badges = badges,
                    followerCount = followerCount,
                    followingCount = followingCount,
                    videoCount = videoCount,
                    threadCount = threadCount,
                    totalHypesReceived = totalHypesReceived,
                    totalCoolsReceived = totalCoolsReceived,
                    totalViews = totalViews,
                    totalLikes = totalHypesReceived,
                    totalVideos = videoCount,
                    createdAt = createdAt,
                    lastActiveAt = lastActiveAt
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

// MARK: - Video Creation Types

sealed class VideoCreationContext {
    data class NewThread(val title: String, val description: String) : VideoCreationContext()
    data class ReplyToThread(val parentThreadID: String, val parentVideoID: String) : VideoCreationContext()
    data class RespondToChild(val parentThreadID: String, val parentVideoID: String) : VideoCreationContext()

    val contentType: ContentType
        get() = when (this) {
            is NewThread -> ContentType.THREAD
            is ReplyToThread -> ContentType.CHILD
            is RespondToChild -> ContentType.STEPCHILD
        }
}

// MARK: - Validation Types

data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList()
) {
    companion object {
        fun success() = ValidationResult(true)
        fun failure(vararg errors: String) = ValidationResult(false, errors.toList())
    }
}

// MARK: - Additional Foundation Types

enum class EngagementRewardType(val displayName: String) {
    CLOUT_GAIN("Clout Gained"),
    MILESTONE_REACHED("Milestone Reached"),
    TIER_PROMOTION("Tier Promotion"),
    BADGE_EARNED("Badge Earned"),
    STREAK_BONUS("Streak Bonus");
}

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

/**
 * Engagement metrics with legacy parameter names for compatibility
 */
data class EngagementMetrics(
    val hypeCount: Int,
    val coolCount: Int,
    val viewCount: Int,
    val shareCount: Int = 0,
    val replyCount: Int = 0
) {
    // Modern property names for new code
    val hype: Int get() = hypeCount
    val cool: Int get() = coolCount
    val views: Int get() = viewCount
    val shares: Int get() = shareCount
    val replies: Int get() = replyCount

    val netScore: Int
        get() = hypeCount - coolCount

    val engagementRatio: Double
        get() {
            val total = hypeCount + coolCount
            return if (total > 0) hypeCount.toDouble() / total.toDouble() else 0.0
        }
}