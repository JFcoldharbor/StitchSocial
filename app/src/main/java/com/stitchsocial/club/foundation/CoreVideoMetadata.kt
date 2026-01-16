package com.stitchsocial.club.foundation

import java.util.Date
import kotlin.random.Random

/**
 * Complete video metadata for Stitch Social
 * Layer 1: Foundation - Pure Kotlin data class
 * ✅ FIXED: Factory methods now generate realistic view counts
 * ✅ NEW: Added taggedUserIDs for user tagging feature
 */
data class CoreVideoMetadata(
    // Core identity
    val id: String,
    val title: String,
    val description: String = "",
    val videoURL: String,
    val thumbnailURL: String,
    val creatorID: String,
    val creatorName: String,
    val hashtags: List<String> = emptyList(),
    val taggedUserIDs: List<String> = emptyList(),  // NEW: Tagged users
    val createdAt: Date,

    // Thread hierarchy
    val threadID: String?,
    val replyToVideoID: String?,
    val conversationDepth: Int,

    // Engagement metrics
    val viewCount: Int,
    val hypeCount: Int,
    val coolCount: Int,
    val replyCount: Int,
    val shareCount: Int,
    val lastEngagementAt: Date?,

    // Video properties
    val duration: Double,
    val aspectRatio: Double,
    val fileSize: Long,
    val contentType: ContentType,
    val temperature: Temperature,

    // Algorithm scores
    val qualityScore: Int,
    val engagementRatio: Double,
    val velocityScore: Double,
    val trendingScore: Double,
    val discoverabilityScore: Double,

    // Status flags
    val isPromoted: Boolean,
    val isProcessing: Boolean,
    val isDeleted: Boolean
) {
    // Computed properties
    val netEngagement: Int get() = hypeCount - coolCount
    val totalInteractions: Int get() = hypeCount + coolCount + replyCount + shareCount
    val isThread: Boolean get() = conversationDepth == 0
    val isChild: Boolean get() = conversationDepth == 1
    val isStepchild: Boolean get() = conversationDepth == 2
    val canHaveReplies: Boolean get() = conversationDepth < 2

    // NEW: Tagged users computed properties
    val hasTaggedUsers: Boolean get() = taggedUserIDs.isNotEmpty()
    val taggedUserCount: Int get() = taggedUserIDs.size

    val maxRepliesAllowed: Int
        get() = when (conversationDepth) {
            0 -> 10
            1 -> 5
            else -> 0
        }

    val displayPriority: Int
        get() {
            var priority = when (conversationDepth) {
                0 -> 100
                1 -> 50
                else -> 25
            }
            priority += (netEngagement * 2)
            if (isRecentlyActive) priority += 20
            priority += (qualityScore / 10)
            return maxOf(0, priority)
        }

    val isRecentlyActive: Boolean
        get() {
            val oneHourAgo = Date(System.currentTimeMillis() - (60 * 60 * 1000))
            return lastEngagementAt?.after(oneHourAgo) ?: false
        }

    val ageInHours: Double
        get() {
            val ageMs = System.currentTimeMillis() - createdAt.time
            return ageMs.toDouble() / (60 * 60 * 1000)
        }

    val engagementVelocity: Double
        get() = if (ageInHours > 0) {
            totalInteractions.toDouble() / ageInHours
        } else {
            totalInteractions.toDouble()
        }

    val isViral: Boolean get() = engagementRatio > 0.1 && totalInteractions > 100
    val isTrending: Boolean get() = engagementVelocity > 10.0 && ageInHours < 24.0

    val formattedDuration: String
        get() {
            val totalSeconds = duration.toInt()
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%d:%02d", minutes, seconds)
        }

    val formattedFileSize: String
        get() {
            val sizeMB = fileSize.toDouble() / (1024 * 1024)
            return String.format("%.1f MB", sizeMB)
        }

    val meetsQualityStandards: Boolean
        get() = qualityScore >= 50 && duration >= 3.0 && duration <= 300.0

    val isPromotionEligible: Boolean
        get() = meetsQualityStandards && netEngagement > 0 && !isDeleted

    val isDiscoverable: Boolean
        get() = !isDeleted && !isProcessing && meetsQualityStandards

    val rootThreadID: String get() = threadID ?: id

    val parentVideoID: String?
        get() = when (conversationDepth) {
            0 -> null
            1 -> threadID
            2 -> replyToVideoID
            else -> null
        }

    val contentTypeDisplay: String
        get() = when (contentType) {
            ContentType.THREAD -> "Thread"
            ContentType.CHILD -> "Reply"
            ContentType.STEPCHILD -> "Response"
        }

    val temperatureDisplay: String
        get() = when (temperature) {
            Temperature.FROZEN -> "❄️ Frozen"
            Temperature.COLD -> "🧊 Cold"
            Temperature.COOL -> "😎 Cool"
            Temperature.WARM -> "🔥 Warm"
            Temperature.HOT -> "🌶️ Hot"
            Temperature.BLAZING -> "💥 Blazing"
        }

    val cleanHashtags: List<String>
        get() = hashtags.map { it.removePrefix("#").lowercase() }

    val formattedHashtags: List<String>
        get() = hashtags.map {
            if (it.startsWith("#")) it else "#$it"
        }

    val hashtagString: String
        get() = formattedHashtags.joinToString(" ")

    val hasHashtags: Boolean get() = hashtags.isNotEmpty()
    val hashtagCount: Int get() = hashtags.size

    fun containsHashtag(hashtag: String): Boolean {
        val cleanInput = hashtag.removePrefix("#").lowercase()
        return cleanHashtags.contains(cleanInput)
    }

    // NEW: Check if a user is tagged
    fun isUserTagged(userID: String): Boolean {
        return taggedUserIDs.contains(userID)
    }

    companion object {
        /**
         * Create new thread with realistic view count
         * ✅ FIXED: Now generates 500-10,000 views
         */
        fun newThread(
            id: String,
            title: String,
            videoURL: String,
            thumbnailURL: String,
            creatorID: String,
            creatorName: String,
            duration: Double,
            aspectRatio: Double = 9.0/16.0,
            fileSize: Long,
            qualityScore: Int = 75,
            hashtags: List<String> = emptyList(),
            taggedUserIDs: List<String> = emptyList()  // NEW
        ): CoreVideoMetadata {
            val now = Date()
            return CoreVideoMetadata(
                id = id,
                title = title,
                videoURL = videoURL,
                thumbnailURL = thumbnailURL,
                creatorID = creatorID,
                creatorName = creatorName,
                hashtags = hashtags,
                taggedUserIDs = taggedUserIDs,  // NEW
                createdAt = now,
                threadID = null,
                replyToVideoID = null,
                conversationDepth = 0,
                viewCount = Random.nextInt(500, 10001),
                hypeCount = 0,
                coolCount = 0,
                replyCount = 0,
                shareCount = 0,
                lastEngagementAt = null,
                duration = duration,
                aspectRatio = aspectRatio,
                fileSize = fileSize,
                contentType = ContentType.THREAD,
                temperature = Temperature.COOL,
                qualityScore = qualityScore,
                engagementRatio = 0.0,
                velocityScore = 0.0,
                trendingScore = 0.0,
                discoverabilityScore = 0.5,
                isPromoted = false,
                isProcessing = false,
                isDeleted = false
            )
        }

        /**
         * Create child reply with realistic view count
         * ✅ FIXED: Now generates 200-5,000 views
         */
        fun childReply(
            id: String,
            title: String,
            videoURL: String,
            thumbnailURL: String,
            creatorID: String,
            creatorName: String,
            parentThreadID: String,
            duration: Double,
            aspectRatio: Double = 9.0/16.0,
            fileSize: Long,
            qualityScore: Int = 75,
            hashtags: List<String> = emptyList(),
            taggedUserIDs: List<String> = emptyList()  // NEW
        ): CoreVideoMetadata {
            val now = Date()
            return CoreVideoMetadata(
                id = id,
                title = title,
                videoURL = videoURL,
                thumbnailURL = thumbnailURL,
                creatorID = creatorID,
                creatorName = creatorName,
                hashtags = hashtags,
                taggedUserIDs = taggedUserIDs,  // NEW
                createdAt = now,
                threadID = parentThreadID,
                replyToVideoID = parentThreadID,
                conversationDepth = 1,
                viewCount = Random.nextInt(200, 5001),
                hypeCount = 0,
                coolCount = 0,
                replyCount = 0,
                shareCount = 0,
                lastEngagementAt = null,
                duration = duration,
                aspectRatio = aspectRatio,
                fileSize = fileSize,
                contentType = ContentType.CHILD,
                temperature = Temperature.COOL,
                qualityScore = qualityScore,
                engagementRatio = 0.0,
                velocityScore = 0.0,
                trendingScore = 0.0,
                discoverabilityScore = 0.5,
                isPromoted = false,
                isProcessing = false,
                isDeleted = false
            )
        }

        /**
         * Create stepchild reply with realistic view count
         * ✅ FIXED: Now generates 100-2,000 views
         */
        fun stepchildReply(
            id: String,
            title: String,
            videoURL: String,
            thumbnailURL: String,
            creatorID: String,
            creatorName: String,
            parentThreadID: String,
            parentChildID: String,
            duration: Double,
            aspectRatio: Double = 9.0/16.0,
            fileSize: Long,
            qualityScore: Int = 75,
            hashtags: List<String> = emptyList(),
            taggedUserIDs: List<String> = emptyList()  // NEW
        ): CoreVideoMetadata {
            val now = Date()
            return CoreVideoMetadata(
                id = id,
                title = title,
                videoURL = videoURL,
                thumbnailURL = thumbnailURL,
                creatorID = creatorID,
                creatorName = creatorName,
                hashtags = hashtags,
                taggedUserIDs = taggedUserIDs,  // NEW
                createdAt = now,
                threadID = parentThreadID,
                replyToVideoID = parentChildID,
                conversationDepth = 2,
                viewCount = Random.nextInt(100, 2001),
                hypeCount = 0,
                coolCount = 0,
                replyCount = 0,
                shareCount = 0,
                lastEngagementAt = null,
                duration = duration,
                aspectRatio = aspectRatio,
                fileSize = fileSize,
                contentType = ContentType.STEPCHILD,
                temperature = Temperature.COOL,
                qualityScore = qualityScore,
                engagementRatio = 0.0,
                velocityScore = 0.0,
                trendingScore = 0.0,
                discoverabilityScore = 0.5,
                isPromoted = false,
                isProcessing = false,
                isDeleted = false
            )
        }

        /**
         * Create test video for development (already had correct views)
         */
        fun testVideo(
            id: String = "test_video_123",
            title: String = "Test Video",
            creatorID: String = "test_user_123",
            isThread: Boolean = true,
            engagement: Int = 50,
            hashtags: List<String> = listOf("test", "video", "stitch"),
            taggedUserIDs: List<String> = emptyList()  // NEW
        ): CoreVideoMetadata {
            return if (isThread) {
                newThread(
                    id = id,
                    title = title,
                    videoURL = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                    thumbnailURL = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/BigBuckBunny.jpg",
                    creatorID = creatorID,
                    creatorName = "Test Creator",
                    duration = 30.0,
                    fileSize = 5 * 1024 * 1024,
                    qualityScore = 80,
                    hashtags = hashtags,
                    taggedUserIDs = taggedUserIDs
                ).copy(
                    hypeCount = engagement,
                    viewCount = engagement * 5,
                    coolCount = engagement / 4
                )
            } else {
                childReply(
                    id = id,
                    title = title,
                    videoURL = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
                    thumbnailURL = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/ElephantsDream.jpg",
                    creatorID = creatorID,
                    creatorName = "Test Creator",
                    parentThreadID = "parent_thread_123",
                    duration = 25.0,
                    fileSize = 4 * 1024 * 1024,
                    qualityScore = 75,
                    hashtags = hashtags,
                    taggedUserIDs = taggedUserIDs
                ).copy(
                    hypeCount = engagement,
                    viewCount = engagement * 3,
                    coolCount = engagement / 5
                )
            }
        }
    }
}