package com.example.stitchsocialclub.firebase

/**
 * FirebaseSchema.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 3: Firebase Foundation - Database Schema & Index Definitions
 * Defines Firestore collection structures, validation schemas, and performance indexes
 * Dependencies: Layer 2 (Protocols), Layer 1 (Foundation) only - No external service dependencies
 * Database: stitchfin
 *
 * Exact translation from Swift FirebaseSchema.swift
 */

// MARK: - Firebase Database Schema

/**
 * Centralized database schema definitions for Firestore collections
 * Ensures consistent data structures across the entire stitchfin database
 */
object FirebaseSchema {
    
    // MARK: - Database Configuration
    
    /** Database identifier for stitchfin */
    const val DATABASE_NAME = "stitchfin"
    
    /** Validate database configuration */
    fun validateDatabaseConfig(): Boolean {
        if (DATABASE_NAME.isEmpty()) {
            println("❌ FIREBASE SCHEMA: Database name is empty")
            return false
        }
        
        println("✅ FIREBASE SCHEMA: Configured for database: $DATABASE_NAME")
        return true
    }
    
    // MARK: - Collection Names
    
    object Collections {
        const val VIDEOS = "videos"
        const val USERS = "users"
        const val THREADS = "threads"
        const val ENGAGEMENT = "engagement"
        const val INTERACTIONS = "interactions"
        const val TAP_PROGRESS = "tapProgress"
        const val NOTIFICATIONS = "notifications"
        const val FOLLOWING = "following"
        const val USER_BADGES = "userBadges"
        const val PROGRESSION = "progression"
        const val ANALYTICS = "analytics"
        const val COMMENTS = "comments"
        const val REPORTS = "reports"
        const val CACHE = "cache"
        
        /** Get full collection path for stitchfin database */
        fun fullPath(collection: String): String {
            return "projects/stitchbeta-8bbfe/databases/$DATABASE_NAME/documents/$collection"
        }
        
        /** Validate all collection names */
        fun validateCollections(): List<String> {
            val collections = listOf(
                VIDEOS, USERS, THREADS, ENGAGEMENT, INTERACTIONS,
                TAP_PROGRESS, NOTIFICATIONS, FOLLOWING, USER_BADGES,
                PROGRESSION, ANALYTICS, COMMENTS, REPORTS, CACHE
            )
            
            val invalidCollections = collections.filter { it.isEmpty() }
            
            if (invalidCollections.isEmpty()) {
                println("✅ FIREBASE SCHEMA: All ${collections.size} collections validated for $DATABASE_NAME")
            } else {
                println("❌ FIREBASE SCHEMA: Invalid collections found: $invalidCollections")
            }
            
            return invalidCollections
        }
    }
    
    // MARK: - Video Document Schema
    
    object VideoDocument {
        // Core video fields
        const val ID = "id"
        const val TITLE = "title"
        const val VIDEO_URL = "videoURL"
        const val THUMBNAIL_URL = "thumbnailURL"
        const val CREATOR_ID = "creatorID"
        const val CREATOR_NAME = "creatorName"
        const val CREATED_AT = "createdAt"
        const val UPDATED_AT = "updatedAt"
        
        // Thread hierarchy fields
        const val THREAD_ID = "threadID"
        const val REPLY_TO_VIDEO_ID = "replyToVideoID"
        const val CONVERSATION_DEPTH = "conversationDepth"
        const val CHILD_VIDEO_IDS = "childVideoIDs"
        const val STEPCHILD_VIDEO_IDS = "stepchildVideoIDs"
        
        // Engagement fields
        const val VIEW_COUNT = "viewCount"
        const val HYPE_COUNT = "hypeCount"
        const val COOL_COUNT = "coolCount"
        const val REPLY_COUNT = "replyCount"
        const val SHARE_COUNT = "shareCount"
        const val LAST_ENGAGEMENT_AT = "lastEngagementAt"
        
        // Metadata fields
        const val DURATION = "duration"
        const val ASPECT_RATIO = "aspectRatio"
        const val FILE_SIZE = "fileSize"
        const val TEMPERATURE = "temperature"
        const val CONTENT_TYPE = "contentType"
        const val QUALITY_SCORE = "qualityScore"
        const val DISCOVERABILITY_SCORE = "discoverabilityScore"
        const val IS_PROMOTED = "isPromoted"
        
        // Internal fields
        const val IS_INTERNAL_ACCOUNT = "isInternalAccount"
        const val IS_DELETED = "isDeleted"
        const val MODERATION_STATUS = "moderationStatus"
        
        /** Full document path in stitchfin database */
        fun documentPath(videoID: String): String {
            return Collections.fullPath(Collections.VIDEOS) + "/$videoID"
        }
    }
    
    // MARK: - User Document Schema
    
    object UserDocument {
        // Core user fields
        const val ID = "id"
        const val USERNAME = "username"
        const val DISPLAY_NAME = "displayName"
        const val EMAIL = "email"
        const val PROFILE_IMAGE_URL = "profileImageURL"
        const val BIO = "bio"
        const val CREATED_AT = "createdAt"
        const val UPDATED_AT = "updatedAt"
        const val LAST_ACTIVE_AT = "lastActiveAt"
        
        // Tier and status fields
        const val TIER = "tier"
        const val CLOUT = "clout"
        const val IS_VERIFIED = "isVerified"
        const val IS_INTERNAL_ACCOUNT = "isInternalAccount"
        const val IS_BANNED = "isBanned"
        const val IS_PRIVATE = "isPrivate"
        
        // Statistics fields
        const val FOLLOWER_COUNT = "followerCount"
        const val FOLLOWING_COUNT = "followingCount"
        const val VIDEO_COUNT = "videoCount"
        const val THREAD_COUNT = "threadCount"
        const val TOTAL_HYPES_RECEIVED = "totalHypesReceived"
        const val TOTAL_COOLS_RECEIVED = "totalCoolsReceived"
        const val DELETED_VIDEO_COUNT = "deletedVideoCount"
        
        // Settings fields
        const val NOTIFICATION_SETTINGS = "notificationSettings"
        const val PRIVACY_SETTINGS = "privacySettings"
        const val CONTENT_PREFERENCES = "contentPreferences"
        
        /** Full document path in stitchfin database */
        fun documentPath(userID: String): String {
            return Collections.fullPath(Collections.USERS) + "/$userID"
        }
    }
    
    // MARK: - Thread Document Schema
    
    object ThreadDocument {
        // Core thread fields
        const val ID = "id"
        const val TITLE = "title"
        const val DESCRIPTION = "description"
        const val CREATOR_ID = "creatorID"
        const val CREATED_AT = "createdAt"
        const val UPDATED_AT = "updatedAt"
        const val LAST_ACTIVITY_AT = "lastActivityAt"
        
        // Thread structure fields
        const val PARENT_VIDEO_ID = "parentVideoID"
        const val CHILD_VIDEO_IDS = "childVideoIDs"
        const val STEPCHILD_VIDEO_IDS = "stepchildVideoIDs"
        const val CONVERSATION_DEPTH = "conversationDepth"
        const val MAX_DEPTH = "maxDepth"
        
        // Thread status fields
        const val IS_LOCKED = "isLocked"
        const val IS_ARCHIVED = "isArchived"
        const val TEMPERATURE = "temperature"
        const val TRENDING = "trending"
        const val PARTICIPANT_COUNT = "participantCount"
        
        // Engagement summary
        const val TOTAL_REPLIES = "totalReplies"
        const val TOTAL_ENGAGEMENT = "totalEngagement"
        const val AVERAGE_ENGAGEMENT = "averageEngagement"
        
        /** Full document path in stitchfin database */
        fun documentPath(threadID: String): String {
            return Collections.fullPath(Collections.THREADS) + "/$threadID"
        }
    }
    
    // MARK: - Engagement Document Schema
    
    object EngagementDocument {
        // Core engagement fields
        const val VIDEO_ID = "videoID"
        const val CREATOR_ID = "creatorID"
        const val HYPE_COUNT = "hypeCount"
        const val COOL_COUNT = "coolCount"
        const val SHARE_COUNT = "shareCount"
        const val REPLY_COUNT = "replyCount"
        const val VIEW_COUNT = "viewCount"
        const val LAST_ENGAGEMENT_AT = "lastEngagementAt"
        const val UPDATED_AT = "updatedAt"
        
        // Calculated fields
        const val NET_SCORE = "netScore"
        const val ENGAGEMENT_RATIO = "engagementRatio"
        const val VELOCITY_SCORE = "velocityScore"
        const val TRENDING_SCORE = "trendingScore"
        
        /** Full document path in stitchfin database */
        fun documentPath(videoID: String): String {
            return Collections.fullPath(Collections.ENGAGEMENT) + "/$videoID"
        }
    }
    
    // MARK: - Interaction Document Schema (Subcollection)
    
    object InteractionDocument {
        const val USER_ID = "userID"
        const val VIDEO_ID = "videoID"
        const val ENGAGEMENT_TYPE = "engagementType"
        const val TIMESTAMP = "timestamp"
        const val CURRENT_TAPS = "currentTaps"
        const val REQUIRED_TAPS = "requiredTaps"
        const val IS_COMPLETED = "isCompleted"
        const val IMPACT_VALUE = "impactValue"
        
        /** Full document path in stitchfin database */
        fun documentPath(interactionID: String): String {
            return Collections.fullPath(Collections.INTERACTIONS) + "/$interactionID"
        }
    }
    
    // MARK: - Tap Progress Document Schema
    
    object TapProgressDocument {
        const val VIDEO_ID = "videoID"
        const val USER_ID = "userID"
        const val ENGAGEMENT_TYPE = "engagementType"
        const val CURRENT_TAPS = "currentTaps"
        const val REQUIRED_TAPS = "requiredTaps"
        const val LAST_TAP_TIME = "lastTapTime"
        const val IS_COMPLETED = "isCompleted"
        const val CREATED_AT = "createdAt"
        const val UPDATED_AT = "updatedAt"
        
        /** Full document path in stitchfin database */
        fun documentPath(progressID: String): String {
            return Collections.fullPath(Collections.TAP_PROGRESS) + "/$progressID"
        }
    }
    
    // MARK: - Notification Document Schema
    
    object NotificationDocument {
        const val ID = "id"
        const val RECIPIENT_ID = "recipientID"
        const val SENDER_ID = "senderID"
        const val TYPE = "type"
        const val TITLE = "title"
        const val MESSAGE = "message"
        const val PAYLOAD = "payload"
        const val IS_READ = "isRead"
        const val CREATED_AT = "createdAt"
        const val READ_AT = "readAt"
        const val EXPIRES_AT = "expiresAt"
        
        /** Full document path in stitchfin database */
        fun documentPath(notificationID: String): String {
            return Collections.fullPath(Collections.NOTIFICATIONS) + "/$notificationID"
        }
    }
    
    // MARK: - Following Document Schema
    
    object FollowingDocument {
        const val FOLLOWER_ID = "followerID"
        const val FOLLOWING_ID = "followingID"
        const val CREATED_AT = "createdAt"
        const val IS_ACTIVE = "isActive"
        const val NOTIFICATION_ENABLED = "notificationEnabled"
        
        /** Full document path in stitchfin database */
        fun documentPath(followingID: String): String {
            return Collections.fullPath(Collections.FOLLOWING) + "/$followingID"
        }
    }
    
    // MARK: - Badge & Progression Schema
    
    object UserBadgesDocument {
        const val USER_ID = "userID"
        const val EARNED_BADGES = "earnedBadges"
        const val BADGE_PROGRESS = "badgeProgress"
        const val TOTAL_BADGES_EARNED = "totalBadgesEarned"
        const val LAST_BADGE_EARNED = "lastBadgeEarned"
        const val UPDATED_AT = "updatedAt"
        
        /** Full document path in stitchfin database */
        fun documentPath(userID: String): String {
            return Collections.fullPath(Collections.USER_BADGES) + "/$userID"
        }
    }
    
    object ProgressionDocument {
        const val USER_ID = "userID"
        const val CURRENT_TIER = "currentTier"
        const val CLOUT = "clout"
        const val TOTAL_ENGAGEMENT = "totalEngagement"
        const val TIER_PROGRESS = "tierProgress"
        const val NEXT_TIER_REQUIREMENTS = "nextTierRequirements"
        const val LAST_TIER_UPDATE = "lastTierUpdate"
        const val UPDATED_AT = "updatedAt"
        
        /** Full document path in stitchfin database */
        fun documentPath(userID: String): String {
            return Collections.fullPath(Collections.PROGRESSION) + "/$userID"
        }
    }
    
    // MARK: - Performance Index Definitions for stitchfin
    
    /** Required Firestore composite indexes for optimal query performance in stitchfin database */
    object RequiredIndexes {
        
        // Videos collection indexes
        val videosByCreator = listOf(
            VideoDocument.CREATOR_ID,
            VideoDocument.CREATED_AT
        )
        
        val videosByThread = listOf(
            VideoDocument.THREAD_ID,
            VideoDocument.CONVERSATION_DEPTH,
            VideoDocument.CREATED_AT
        )
        
        val videosByEngagement = listOf(
            VideoDocument.HYPE_COUNT,
            VideoDocument.COOL_COUNT,
            VideoDocument.CREATED_AT
        )
        
        val videosByTemperature = listOf(
            VideoDocument.TEMPERATURE,
            VideoDocument.CREATED_AT
        )
        
        val threadHierarchy = listOf(
            VideoDocument.REPLY_TO_VIDEO_ID,
            VideoDocument.CONVERSATION_DEPTH,
            VideoDocument.CREATED_AT
        )
        
        // User engagement indexes
        val userEngagementByVideo = listOf(
            InteractionDocument.USER_ID,
            InteractionDocument.VIDEO_ID,
            InteractionDocument.TIMESTAMP
        )
        
        val engagementByType = listOf(
            InteractionDocument.ENGAGEMENT_TYPE,
            InteractionDocument.TIMESTAMP
        )
        
        // Following system indexes
        val followersByUser = listOf(
            FollowingDocument.FOLLOWING_ID,
            FollowingDocument.CREATED_AT
        )
        
        val followingByUser = listOf(
            FollowingDocument.FOLLOWER_ID,
            FollowingDocument.CREATED_AT
        )
        
        // Notification indexes
        val notificationsByRecipient = listOf(
            NotificationDocument.RECIPIENT_ID,
            NotificationDocument.IS_READ,
            NotificationDocument.CREATED_AT
        )
        
        val notificationsByType = listOf(
            NotificationDocument.TYPE,
            NotificationDocument.CREATED_AT
        )
        
        // Thread performance indexes
        val threadsByActivity = listOf(
            ThreadDocument.LAST_ACTIVITY_AT,
            ThreadDocument.TRENDING
        )
        
        val threadsByTemperature = listOf(
            ThreadDocument.TEMPERATURE,
            ThreadDocument.PARTICIPANT_COUNT
        )
        
        /** Generate index creation commands for stitchfin database */
        fun generateIndexCommands(): List<String> {
            return listOf(
                "firebase firestore:indexes --project=stitchbeta-8bbfe --database=stitchfin",
                "// Add these composite indexes to firestore.indexes.json",
                "// Database: stitchfin",
                "// Collection: videos - Creator timeline",
                "// Collection: videos - Thread hierarchy",
                "// Collection: interactions - User engagement",
                "// Collection: following - Social connections",
                "// Collection: notifications - User notifications"
            )
        }
    }
    
    // MARK: - Data Validation Rules
    
    /** Validation constraints for document fields in stitchfin database */
    object ValidationRules {
        
        // Video validation
        const val MAX_VIDEO_TITLE_LENGTH = 100
        const val MIN_VIDEO_TITLE_LENGTH = 1
        const val MAX_VIDEO_DURATION = 300.0 // 5 minutes in seconds
        const val MAX_VIDEO_FILE_SIZE = 100L * 1024 * 1024 // 100MB
        val ALLOWED_VIDEO_FORMATS = listOf("mp4", "mov", "m4v")
        
        // User validation
        const val MAX_USERNAME_LENGTH = 20
        const val MIN_USERNAME_LENGTH = 3
        const val MAX_DISPLAY_NAME_LENGTH = 50
        const val MAX_BIO_LENGTH = 150
        const val USERNAME_PATTERN = "^[a-zA-Z0-9_]+$"
        
        // Thread validation
        const val MAX_THREAD_TITLE_LENGTH = 100
        const val MIN_THREAD_TITLE_LENGTH = 3
        const val MAX_CONVERSATION_DEPTH = 2
        const val MAX_CHILDREN_PER_THREAD = 10
        const val MAX_STEPCHILDREN_PER_CHILD = 10
        
        // Engagement validation
        const val MAX_TAPS_REQUIRED = 10
        const val MIN_TAPS_REQUIRED = 1
        const val ENGAGEMENT_COOLDOWN_SECONDS = 1
        const val MAX_ENGAGEMENT_RATE_PER_MINUTE = 60
        
        // Notification validation
        const val MAX_NOTIFICATION_TITLE_LENGTH = 100
        const val MAX_NOTIFICATION_MESSAGE_LENGTH = 500
        const val NOTIFICATION_EXPIRY_DAYS = 30
        
        /** Validate username format */
        fun isValidUsername(username: String): Boolean {
            return username.length in MIN_USERNAME_LENGTH..MAX_USERNAME_LENGTH &&
                   username.matches(Regex(USERNAME_PATTERN))
        }
        
        /** Validate video duration */
        fun isValidVideoDuration(duration: Double): Boolean {
            return duration > 0 && duration <= MAX_VIDEO_DURATION
        }
        
        /** Validate video file size */
        fun isValidVideoFileSize(fileSize: Long): Boolean {
            return fileSize > 0 && fileSize <= MAX_VIDEO_FILE_SIZE
        }
        
        /** Validate conversation depth */
        fun isValidConversationDepth(depth: Int): Boolean {
            return depth in 0..MAX_CONVERSATION_DEPTH
        }
    }
    
    // MARK: - Caching Configuration
    
    /** Caching strategies for different data types in stitchfin database */
    object CacheConfiguration {
        
        // Video content caching
        const val VIDEO_CACHE_TTL = 300L // 5 minutes
        const val THUMBNAIL_CACHE_TTL = 3600L // 1 hour
        const val PROFILE_IMAGE_CACHE_TTL = 1800L // 30 minutes
        
        // Engagement data caching
        const val ENGAGEMENT_CACHE_TTL = 30L // 30 seconds
        const val TAP_PROGRESS_CACHE_TTL = 60L // 1 minute
        
        // User data caching
        const val USER_PROFILE_CACHE_TTL = 600L // 10 minutes
        const val FOLLOWING_LIST_CACHE_TTL = 300L // 5 minutes
        
        // Thread data caching
        const val THREAD_STRUCTURE_CACHE_TTL = 180L // 3 minutes
        const val THREAD_LIST_CACHE_TTL = 120L // 2 minutes
        
        /** Generate cache key for stitchfin database */
        fun cacheKey(collection: String, document: String): String {
            return "stitchfin_${collection}_$document"
        }
    }
    
    // MARK: - Database Operations for stitchfin
    
    /** Standard database operation patterns for stitchfin database */
    object Operations {
        
        // Batch write patterns
        const val MAX_BATCH_SIZE = 500
        const val BATCH_RETRY_ATTEMPTS = 3
        const val BATCH_TIMEOUT_SECONDS = 30
        
        // Transaction patterns
        const val MAX_TRANSACTION_RETRIES = 5
        const val TRANSACTION_TIMEOUT_SECONDS = 60
        
        // Realtime listener patterns
        const val MAX_LISTENERS_PER_VIEW = 5
        const val LISTENER_RECONNECT_DELAY_SECONDS = 2
        const val LISTENER_MAX_RECONNECT_ATTEMPTS = 10
        
        /** Generate operation metrics for stitchfin database */
        fun operationMetrics(): Map<String, Any> {
            return mapOf(
                "database" to DATABASE_NAME,
                "maxBatchSize" to MAX_BATCH_SIZE,
                "maxTransactionRetries" to MAX_TRANSACTION_RETRIES,
                "maxListeners" to MAX_LISTENERS_PER_VIEW
            )
        }
    }
    
    // MARK: - Database Initialization
    
    /** Initialize stitchfin database schema */
    fun initializeSchema(): Boolean {
        println("🔧 FIREBASE SCHEMA: Initializing stitchfin database schema...")
        
        val databaseValid = validateDatabaseConfig()
        val collectionsValid = Collections.validateCollections().isEmpty()
        
        return if (databaseValid && collectionsValid) {
            println("✅ FIREBASE SCHEMA: stitchfin database schema initialized successfully")
            println("📊 FIREBASE SCHEMA: Collections: ${Collections.validateCollections().size}")
            println("🔍 FIREBASE SCHEMA: Indexes: ${RequiredIndexes.generateIndexCommands().size}")
            true
        } else {
            println("❌ FIREBASE SCHEMA: stitchfin database schema initialization failed")
            false
        }
    }
}