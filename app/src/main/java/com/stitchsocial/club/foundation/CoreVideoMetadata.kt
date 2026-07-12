package com.stitchsocial.club.foundation

import java.util.Date
import kotlin.random.Random

/**
 * Complete video metadata for Stitch Social
 * Layer 1: Foundation - Pure Kotlin data class
 *
 * UPDATED: Added recordingSource for content authenticity scoring
 * UPDATED: Added collection support fields (collectionID, segmentNumber, segmentTitle, replyTimestamp)
 * UPDATED: Added collectionSegment() factory method
 * UPDATED: Added taggedUserIDs for user tagging feature
 * UPDATED: Added spin-off support
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
    val taggedUserIDs: List<String> = emptyList(),
    val createdAt: Date,

    // Thread hierarchy
    val threadID: String?,
    val replyToVideoID: String?,
    val conversationDepth: Int,
    // True when the thread creator posted this via the continue-thread flow.
    // Continuations form the thread "spine": always ordered before replies,
    // locked chronological, never reordered by engagement. Legacy docs lack
    // the field (decodes false) — see List<CoreVideoMetadata>.threadOrdered
    // for the fallback rule.
    val isContinuation: Boolean = false,

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

    // Boost + category (drives the weighted discovery shuffle; iOS BoostCalculator inputs)
    val boostCoins: Int = 0,
    val boostExpiresAt: Date? = null,
    val freeBoostExpiresAt: Date? = null,
    val primaryCategory: String? = null,

    // Status flags
    val isPromoted: Boolean,
    val isProcessing: Boolean,
    val isDeleted: Boolean,

    // Spin-off support
    val spinOffFromVideoID: String? = null,
    val spinOffFromThreadID: String? = null,
    val spinOffCount: Int = 0,

    // Content Authenticity
    val recordingSource: String = "unknown",  // "inApp", "cameraRoll", "unknown"

    // Collection Support
    val collectionID: String? = null,         // If part of a collection, the collection's ID
    val segmentNumber: Int? = null,           // Order within collection (1-based)
    val segmentTitle: String? = null,         // Optional title for this segment
    val isCollectionSegment: Boolean = false,  // True if this video is a collection segment
    val replyTimestamp: Double? = null,        // Timestamp in parent video this reply references

    // CDN / HLS playback (Stephen pipeline — iOS parity, see project_stitch_cdn_integration)
    val hlsURL: String? = null,                // ABR master (.m3u8) on public CloudFront — cellular win
    val mp4URL: String? = null,                // faststart MP4 fallback on public CloudFront
    val status: String? = null,                // "processing" -> "published"; null on legacy docs

    // Challenge / Giveaway (iOS parity — see project_stitch_challenge)
    val challenge: com.stitchsocial.club.challenge.Challenge? = null,   // set on the thread HEAD
    val challengeThreadID: String? = null,     // set on an ENTRY (child) — the challenge head id
    val challengeStatus: com.stitchsocial.club.challenge.ChallengeEntryStatus? = null,  // entered/qualified/won

    // Event posts (iOS parity — see project_stitch_social; nested `event` map on the thread HEAD)
    val event: com.stitchsocial.club.events.StitchEvent? = null
) {
    // Computed properties

    /** True if this video is a challenge head. */
    val isChallenge: Boolean get() = challenge != null
    /** True if this is a challenge head still open for entries. */
    val isChallengeActive: Boolean get() = challenge?.isActive == true
    /** True if this video is an entry in some challenge. */
    val isChallengeEntry: Boolean get() = challengeThreadID != null

    /** True if this thread head is an event post. */
    val isEvent: Boolean get() = event != null

    /**
     * Single source of truth for which URL to hand a player. Precedence:
     * live HLS (ABR) -> faststart MP4 fallback -> legacy videoURL.
     * Prefer HLS whenever it exists and is live — don't trust the client-flipped
     * `status` alone (the poller that sets it is unreliable on bad signal); also
     * treat HLS as live once the doc is old enough that transcode has certainly
     * finished (~15s measured; 45s safe margin). Self-heals docs stuck at
     * "processing". Returns a URL string for MediaItem.fromUri.
     */
    val playbackURL: String
        get() {
            val hls = hlsURL
            if (!hls.isNullOrEmpty()) {
                val transcodeLikelyDone = (Date().time - createdAt.time) > 45_000L
                if (status == "published" || transcodeLikelyDone) return hls
            }
            val mp4 = mp4URL
            if (!mp4.isNullOrEmpty()) return mp4
            return videoURL
        }

    val netEngagement: Int get() = hypeCount - coolCount
    val totalInteractions: Int get() = hypeCount + coolCount + replyCount + shareCount
    val isThread: Boolean get() = conversationDepth == 0
    val isChild: Boolean get() = conversationDepth == 1
    val isStepchild: Boolean get() = conversationDepth == 2
    val canHaveReplies: Boolean get() = conversationDepth < 2

    // Spin-off computed properties
    val isSpinOff: Boolean get() = spinOffFromVideoID != null
    val hasSpinOffs: Boolean get() = spinOffCount > 0

    // Tagged users computed properties
    val hasTaggedUsers: Boolean get() = taggedUserIDs.isNotEmpty()
    val taggedUserCount: Int get() = taggedUserIDs.size

    // Collection computed properties
    /** Display title for collection segments - uses segmentTitle if available, falls back to "Part N" */
    val segmentDisplayTitle: String
        get() {
            val st = segmentTitle
            if (!st.isNullOrEmpty()) return st
            val num = segmentNumber
            if (num != null) return "Part $num"
            return if (title.isEmpty()) "Untitled" else title
        }

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
        get() = hashtags.map { if (it.startsWith("#")) it else "#$it" }

    val hashtagString: String
        get() = formattedHashtags.joinToString(" ")

    val hasHashtags: Boolean get() = hashtags.isNotEmpty()
    val hashtagCount: Int get() = hashtags.size

    fun containsHashtag(hashtag: String): Boolean {
        val cleanInput = hashtag.removePrefix("#").lowercase()
        return cleanHashtags.contains(cleanInput)
    }

    fun isUserTagged(userID: String): Boolean {
        return taggedUserIDs.contains(userID)
    }

    companion object {
        /**
         * Create new thread with realistic view count
         */
        fun newThread(
            id: String,
            title: String,
            videoURL: String,
            thumbnailURL: String,
            creatorID: String,
            creatorName: String,
            duration: Double,
            aspectRatio: Double = 9.0 / 16.0,
            fileSize: Long,
            qualityScore: Int = 75,
            hashtags: List<String> = emptyList(),
            taggedUserIDs: List<String> = emptyList(),
            recordingSource: String = "unknown"
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
                taggedUserIDs = taggedUserIDs,
                createdAt = now,
                threadID = id,
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
                isDeleted = false,
                spinOffFromVideoID = null,
                spinOffFromThreadID = null,
                spinOffCount = 0,
                recordingSource = recordingSource
            )
        }

        /**
         * Create child reply with realistic view count
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
            aspectRatio: Double = 9.0 / 16.0,
            fileSize: Long,
            qualityScore: Int = 75,
            hashtags: List<String> = emptyList(),
            taggedUserIDs: List<String> = emptyList(),
            recordingSource: String = "unknown"
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
                taggedUserIDs = taggedUserIDs,
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
                isDeleted = false,
                spinOffFromVideoID = null,
                spinOffFromThreadID = null,
                spinOffCount = 0,
                recordingSource = recordingSource
            )
        }

        /**
         * Create stepchild reply with realistic view count
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
            aspectRatio: Double = 9.0 / 16.0,
            fileSize: Long,
            qualityScore: Int = 75,
            hashtags: List<String> = emptyList(),
            taggedUserIDs: List<String> = emptyList(),
            recordingSource: String = "unknown"
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
                taggedUserIDs = taggedUserIDs,
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
                isDeleted = false,
                spinOffFromVideoID = null,
                spinOffFromThreadID = null,
                spinOffCount = 0,
                recordingSource = recordingSource
            )
        }

        /**
         * Create spin-off thread from another video
         * A spin-off is a NEW thread (depth 0) that references a source video
         */
        fun spinOffThread(
            id: String,
            title: String,
            videoURL: String,
            thumbnailURL: String,
            creatorID: String,
            creatorName: String,
            spinOffFromVideoID: String,
            spinOffFromThreadID: String,
            duration: Double,
            aspectRatio: Double = 9.0 / 16.0,
            fileSize: Long,
            qualityScore: Int = 75,
            hashtags: List<String> = emptyList(),
            taggedUserIDs: List<String> = emptyList(),
            recordingSource: String = "unknown"
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
                taggedUserIDs = taggedUserIDs,
                createdAt = now,
                threadID = id, // Self-referential - this IS a new thread
                replyToVideoID = null,
                conversationDepth = 0,
                viewCount = Random.nextInt(300, 8001),
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
                isDeleted = false,
                spinOffFromVideoID = spinOffFromVideoID,
                spinOffFromThreadID = spinOffFromThreadID,
                spinOffCount = 0,
                recordingSource = recordingSource
            )
        }

        /**
         * Create a collection segment video
         * Parameters ordered to match CollectionPlayerViewModel call pattern
         */
        fun collectionSegment(
            collectionID: String = "",
            segmentNumber: Int = 1,
            segmentTitle: String? = null,
            segmentID: String? = null,
            videoURL: String = "",
            thumbnailURL: String = "",
            duration: Double = 0.0,
            creatorID: String = "",
            creatorName: String = "",
            fileSize: Long = 0,
            id: String? = null,
            title: String? = null,
            createdAt: Date = Date()
        ): CoreVideoMetadata {
            val finalID = id ?: segmentID ?: java.util.UUID.randomUUID().toString()
            val finalTitle = title ?: segmentTitle ?: "Part $segmentNumber"

            return CoreVideoMetadata(
                id = finalID,
                title = finalTitle,
                description = "",
                videoURL = videoURL,
                thumbnailURL = thumbnailURL,
                creatorID = creatorID,
                creatorName = creatorName,
                hashtags = emptyList(),
                taggedUserIDs = emptyList(),
                createdAt = createdAt,
                threadID = finalID,
                replyToVideoID = null,
                conversationDepth = 0,
                viewCount = 0,
                hypeCount = 0,
                coolCount = 0,
                replyCount = 0,
                shareCount = 0,
                lastEngagementAt = null,
                duration = duration,
                aspectRatio = 9.0 / 16.0,
                fileSize = fileSize,
                contentType = ContentType.THREAD,
                temperature = Temperature.COOL,
                qualityScore = 50,
                engagementRatio = 0.5,
                velocityScore = 0.0,
                trendingScore = 0.0,
                discoverabilityScore = 0.5,
                isPromoted = false,
                isProcessing = false,
                isDeleted = false,
                spinOffFromVideoID = null,
                spinOffFromThreadID = null,
                spinOffCount = 0,
                recordingSource = "inApp",
                collectionID = collectionID,
                segmentNumber = segmentNumber,
                segmentTitle = segmentTitle ?: finalTitle,
                isCollectionSegment = true,
                replyTimestamp = null
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
            hashtags: List<String> = listOf("test", "video", "stitch"),
            taggedUserIDs: List<String> = emptyList()
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
                    taggedUserIDs = taggedUserIDs,
                    recordingSource = "inApp"
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
                    taggedUserIDs = taggedUserIDs,
                    recordingSource = "inApp"
                ).copy(
                    hypeCount = engagement,
                    viewCount = engagement * 3,
                    coolCount = engagement / 5
                )
            }
        }
    }
}

/**
 * Thread spine ordering (iOS parity).
 *
 * Product rule for a thread's children:
 * 1. SPINE — the creator's own continuations (isContinuation == true, or the
 *    legacy fallback: a depth-1 child whose creatorID == the thread creator's
 *    ID, for docs written before the field existed). Always first, locked
 *    chronological (createdAt ascending), never reordered by engagement.
 * 2. REPLIES — everyone else's depth-1 children, ranked by engagement:
 *    hype count desc, then view count desc, then createdAt asc.
 * 3. DEPTH >= 2 — stepchildren keep chronological order at the end.
 *
 * Any depth-0 head accidentally present in the list is kept in front.
 */
fun List<CoreVideoMetadata>.threadOrdered(threadCreatorID: String): List<CoreVideoMetadata> {
    val heads = filter { it.conversationDepth <= 0 }.sortedBy { it.createdAt }
    val depthOne = filter { it.conversationDepth == 1 }
    val deeper = filter { it.conversationDepth >= 2 }.sortedBy { it.createdAt }

    val (spine, replies) = depthOne.partition { child ->
        child.isContinuation ||
            (threadCreatorID.isNotEmpty() && child.creatorID == threadCreatorID)
    }

    val orderedSpine = spine.sortedBy { it.createdAt }
    val orderedReplies = replies.sortedWith(
        compareByDescending<CoreVideoMetadata> { it.hypeCount }
            .thenByDescending { it.viewCount }
            .thenBy { it.createdAt }
    )

    return heads + orderedSpine + orderedReplies + deeper
}