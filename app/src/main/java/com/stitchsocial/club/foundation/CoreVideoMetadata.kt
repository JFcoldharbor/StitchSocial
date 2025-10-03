package com.stitchsocial.club.foundation

import java.util.Date

/**
 * Complete video metadata for Stitch Social
 * REPLACES: BasicVideoInfo in CoreTypes.kt
 * Layer 1: Foundation - Pure Kotlin data class with no Android dependencies
 *
 * Provides comprehensive video state including thread hierarchy, engagement metrics,
 * algorithm scores, and all data needed for feed display and social features.
 *
 * Design principles:
 * - Immutable data class with val properties
 * - Thread hierarchy support (Thread → Child → Stepchild)
 * - Complete engagement tracking
 * - Algorithm-ready performance metrics
 * - Factory methods for different creation modes
 */
data class CoreVideoMetadata(
    // ===== CORE IDENTITY =====
    val id: String,
    val title: String,
    val description: String = "",
    val videoURL: String,
    val thumbnailURL: String,
    val creatorID: String,
    val creatorName: String,
    val hashtags: List<String> = emptyList(), // Social hashtags for discovery
    val createdAt: Date,

    // ===== THREAD HIERARCHY (CRITICAL) =====
    val threadID: String?,
    val replyToVideoID: String?,
    val conversationDepth: Int, // 0=Thread, 1=Child, 2=Stepchild

    // ===== ENGAGEMENT METRICS (CRITICAL) =====
    val viewCount: Int,
    val hypeCount: Int,
    val coolCount: Int,
    val replyCount: Int,
    val shareCount: Int,
    val lastEngagementAt: Date?,

    // ===== VIDEO PROPERTIES =====
    val duration: Double, // seconds
    val aspectRatio: Double, // width/height
    val fileSize: Long, // bytes
    val contentType: ContentType,
    val temperature: Temperature,

    // ===== ALGORITHM SCORES =====
    val qualityScore: Int, // 0-100
    val engagementRatio: Double, // engagement/views
    val velocityScore: Double, // engagement/time
    val trendingScore: Double, // combined trending metric
    val discoverabilityScore: Double, // algorithm ranking

    // ===== STATUS FLAGS =====
    val isPromoted: Boolean,
    val isProcessing: Boolean,
    val isDeleted: Boolean
) {
    // ===== COMPUTED PROPERTIES =====

    /**
     * Net engagement score (hypes minus cools)
     */
    val netEngagement: Int
        get() = hypeCount - coolCount

    /**
     * Total interaction count across all engagement types
     */
    val totalInteractions: Int
        get() = hypeCount + coolCount + replyCount + shareCount

    /**
     * Whether this is a root thread (no parent)
     */
    val isThread: Boolean
        get() = conversationDepth == 0

    /**
     * Whether this is a child reply (depth 1)
     */
    val isChild: Boolean
        get() = conversationDepth == 1

    /**
     * Whether this is a stepchild reply (depth 2)
     */
    val isStepchild: Boolean
        get() = conversationDepth == 2

    /**
     * Whether this video can have replies (max depth not reached)
     */
    val canHaveReplies: Boolean
        get() = conversationDepth < 2

    /**
     * Maximum replies allowed for this video
     */
    val maxRepliesAllowed: Int
        get() = when (conversationDepth) {
            0 -> 10 // Threads can have 10 children
            1 -> 5  // Children can have 5 stepchildren
            else -> 0 // Stepchildren cannot have replies
        }

    /**
     * Display priority for feed ordering (higher = more important)
     */
    val displayPriority: Int
        get() {
            var priority = 0

            // Base priority by conversation depth
            priority += when (conversationDepth) {
                0 -> 100 // Threads get highest priority
                1 -> 50  // Children get medium priority
                else -> 25 // Stepchildren get lowest priority
            }

            // Engagement bonus
            priority += (netEngagement * 2)

            // Velocity bonus (recent engagement)
            if (isRecentlyActive) priority += 20

            // Quality bonus
            priority += (qualityScore / 10)

            return maxOf(0, priority)
        }

    /**
     * Whether video has recent engagement (within last hour)
     */
    val isRecentlyActive: Boolean
        get() {
            val oneHourAgo = Date(System.currentTimeMillis() - (60 * 60 * 1000))
            return lastEngagementAt?.after(oneHourAgo) ?: false
        }

    /**
     * Age in hours since creation
     */
    val ageInHours: Double
        get() {
            val ageMs = System.currentTimeMillis() - createdAt.time
            return ageMs.toDouble() / (60 * 60 * 1000)
        }

    /**
     * Engagement rate per hour since creation
     */
    val engagementVelocity: Double
        get() = if (ageInHours > 0) {
            totalInteractions.toDouble() / ageInHours
        } else {
            totalInteractions.toDouble()
        }

    /**
     * Whether video is considered viral (high engagement ratio)
     */
    val isViral: Boolean
        get() = engagementRatio > 0.1 && totalInteractions > 100

    /**
     * Whether video is trending (high velocity and recent)
     */
    val isTrending: Boolean
        get() = engagementVelocity > 10.0 && ageInHours < 24.0

    /**
     * Display duration formatted as MM:SS
     */
    val formattedDuration: String
        get() {
            val totalSeconds = duration.toInt()
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%d:%02d", minutes, seconds)
        }

    /**
     * File size formatted as MB
     */
    val formattedFileSize: String
        get() {
            val sizeMB = fileSize.toDouble() / (1024 * 1024)
            return String.format("%.1f MB", sizeMB)
        }

    // ===== VALIDATION PROPERTIES =====

    /**
     * Whether video meets minimum quality standards
     */
    val meetsQualityStandards: Boolean
        get() = qualityScore >= 50 && duration >= 3.0 && duration <= 300.0

    /**
     * Whether video is suitable for promotion
     */
    val isPromotionEligible: Boolean
        get() = meetsQualityStandards && netEngagement > 0 && !isDeleted

    /**
     * Whether video can be discovered in feeds
     */
    val isDiscoverable: Boolean
        get() = !isDeleted && !isProcessing && meetsQualityStandards

    // ===== THREAD HIERARCHY HELPERS =====

    /**
     * Get the root thread ID (self if thread, threadID if reply)
     */
    val rootThreadID: String
        get() = threadID ?: id

    /**
     * Get parent video ID for navigation
     */
    val parentVideoID: String?
        get() = when (conversationDepth) {
            0 -> null // Threads have no parent
            1 -> threadID // Children's parent is the thread
            2 -> replyToVideoID // Stepchildren's parent is the child
            else -> null
        }

    /**
     * Content type display string
     */
    val contentTypeDisplay: String
        get() = when (contentType) {
            ContentType.THREAD -> "Thread"
            ContentType.CHILD -> "Reply"
            ContentType.STEPCHILD -> "Response"
        }

    /**
     * Temperature display string with emoji
     */
    val temperatureDisplay: String
        get() = when (temperature) {
            Temperature.FROZEN -> "❄️ Frozen"
            Temperature.COLD -> "🧊 Cold"
            Temperature.COOL -> "😎 Cool"
            Temperature.WARM -> "🔥 Warm"
            Temperature.HOT -> "🌶️ Hot"
            Temperature.BLAZING -> "💥 Blazing"
        }

    // ===== HASHTAG HELPERS =====

    /**
     * Get cleaned hashtags without # symbol
     */
    val cleanHashtags: List<String>
        get() = hashtags.map { it.removePrefix("#").lowercase() }

    /**
     * Get formatted hashtags with # symbol for display
     */
    val formattedHashtags: List<String>
        get() = hashtags.map {
            if (it.startsWith("#")) it else "#$it"
        }

    /**
     * Hashtags formatted as a single string for display
     */
    val hashtagString: String
        get() = formattedHashtags.joinToString(" ")

    /**
     * Whether video has hashtags
     */
    val hasHashtags: Boolean
        get() = hashtags.isNotEmpty()

    /**
     * Number of hashtags
     */
    val hashtagCount: Int
        get() = hashtags.size

    /**
     * Check if video contains specific hashtag (case-insensitive)
     */
    fun containsHashtag(hashtag: String): Boolean {
        val cleanInput = hashtag.removePrefix("#").lowercase()
        return cleanHashtags.contains(cleanInput)
    }

    // ===== FACTORY METHODS =====

    companion object {
        /**
         * Create new thread (conversation depth 0)
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
            hashtags: List<String> = emptyList()
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
                createdAt = now,
                threadID = null, // Threads are their own thread
                replyToVideoID = null,
                conversationDepth = 0,
                viewCount = 0,
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
         * Create child reply (conversation depth 1)
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
            hashtags: List<String> = emptyList()
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
                createdAt = now,
                threadID = parentThreadID,
                replyToVideoID = parentThreadID, // Children reply to thread
                conversationDepth = 1,
                viewCount = 0,
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
         * Create stepchild reply (conversation depth 2)
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
            hashtags: List<String> = emptyList()
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
                createdAt = now,
                threadID = parentThreadID,
                replyToVideoID = parentChildID, // Stepchildren reply to child
                conversationDepth = 2,
                viewCount = 0,
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
         * Create test video for development
         */
        fun testVideo(
            id: String = "test_video_123",
            title: String = "Test Video",
            creatorID: String = "test_user_123",
            isThread: Boolean = true,
            engagement: Int = 50,
            hashtags: List<String> = listOf("test", "video", "stitch")
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
                    fileSize = 5 * 1024 * 1024, // 5MB
                    qualityScore = 80,
                    hashtags = hashtags
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
                    fileSize = 4 * 1024 * 1024, // 4MB
                    qualityScore = 75,
                    hashtags = hashtags
                ).copy(
                    hypeCount = engagement,
                    viewCount = engagement * 3,
                    coolCount = engagement / 5
                )
            }
        }
    }
}