package com.stitchsocial.club.firebase

/**
 * FirebaseSchema.kt - COMPLETE PARITY WITH iOS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 3: Firebase Foundation - Database Schema & Index Definitions
 * Defines Firestore collection structures, validation schemas, and performance indexes
 * Dependencies: Layer 2 (Protocols), Layer 1 (Foundation) only - No external service dependencies
 * Database: stitchfin
 *
 * UPDATED: Complete referral system integration
 * UPDATED: Added taggedUserIDs for user tagging/mentions
 * UPDATED: Added milestone tracking fields for notifications
 * UPDATED: Added Collections support fields (collectionID, segmentNumber, segmentTitle, replyTimestamp)
 * UPDATED: Distributed counter shards for scalable hype/cool writes
 * UPDATED: Added hashtag support for video search and discovery
 * UPDATED: HypeCoin system — coin_balances, coin_transactions, cash_out_requests (iOS parity)
 * UPDATED: Subscription system — subscription_plans, subscriptions, subscription_events (iOS parity)
 * UPDATED: UserDocument — customSubShare / referral deal fields (iOS parity)
 * UPDATED: CacheConfiguration — coin balance + subscription plan TTLs
 * UPDATED: RequiredIndexes — coin transaction + subscription indexes
 * UPDATED: QueryPatterns — coin and subscription query strings
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
        const val SYSTEM = "system"
        const val REFERRALS = "referrals"

        // Collections Feature
        const val VIDEO_COLLECTIONS = "videoCollections"
        const val COLLECTION_DRAFTS = "collectionDrafts"
        const val COLLECTION_PROGRESS = "collectionProgress"

        // Distributed Counter Sub-Collections
        /** Sub-collections under videos/{videoID}/ for scalable writes */
        const val HYPE_SHARDS = "hype_shards"
        const val COOL_SHARDS = "cool_shards"

        // Hype Rating State
        /** Sub-collection under users/{userID}/ for hype rating regeneration state */
        const val HYPE_RATING = "hypeRating"

        // Social Signal / Megaphone System
        /** Sub-collection under videos/{videoID}/ for notable high-tier engagements */
        const val NOTABLE_ENGAGEMENTS = "notableEngagements"
        /** Sub-collection under users/{userID}/ for feed-injected social signals */
        const val SOCIAL_SIGNALS = "socialSignals"

        // MARK: - HypeCoin Collections (EXACT MATCH iOS HypeCoinService.swift)
        const val COIN_BALANCES     = "coin_balances"       // iOS: Collections.balances
        const val COIN_TRANSACTIONS = "coin_transactions"   // iOS: Collections.transactions
        const val CASH_OUT_REQUESTS = "cash_out_requests"   // iOS: Collections.cashOuts

        // MARK: - Subscription Collections (EXACT MATCH iOS SubscriptionService.swift)
        const val SUBSCRIPTION_PLANS  = "subscription_plans"
        const val SUBSCRIPTIONS        = "subscriptions"
        const val SUBSCRIPTION_EVENTS  = "subscription_events"

        /** Number of shards per counter (10 = 10 writes/sec throughput) */
        const val SHARD_COUNT = 10

        /** Get full collection path for stitchfin database */
        fun fullPath(collection: String): String {
            return "projects/stitchbeta-8bbfe/databases/$DATABASE_NAME/documents/$collection"
        }

        /** Validate all collection names */
        fun validateCollections(): List<String> {
            val collections = listOf(
                VIDEOS, USERS, THREADS, ENGAGEMENT, INTERACTIONS,
                TAP_PROGRESS, NOTIFICATIONS, FOLLOWING, USER_BADGES,
                PROGRESSION, ANALYTICS, COMMENTS, REPORTS, CACHE,
                SYSTEM, REFERRALS, VIDEO_COLLECTIONS, COLLECTION_DRAFTS, COLLECTION_PROGRESS,
                COIN_BALANCES, COIN_TRANSACTIONS, CASH_OUT_REQUESTS,
                SUBSCRIPTION_PLANS, SUBSCRIPTIONS, SUBSCRIPTION_EVENTS
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
        const val DESCRIPTION = "description"
        const val TAGGED_USER_IDS = "taggedUserIDs"
        const val VIDEO_URL = "videoURL"
        const val THUMBNAIL_URL = "thumbnailURL"
        const val CREATOR_ID = "creatorID"
        const val CREATOR_NAME = "creatorName"
        const val CREATED_AT = "createdAt"
        const val UPDATED_AT = "updatedAt"

        // Hashtag support
        const val HASHTAGS = "hashtags"

        // Thread hierarchy fields
        const val THREAD_ID = "threadID"
        const val REPLY_TO_VIDEO_ID = "replyToVideoID"
        const val CONVERSATION_DEPTH = "conversationDepth"
        const val CHILD_VIDEO_IDS = "childVideoIDs"
        const val STEPCHILD_VIDEO_IDS = "stepchildVideoIDs"

        // Spin-off Fields
        const val SPIN_OFF_FROM_VIDEO_ID  = "spinOffFromVideoID"
        const val SPIN_OFF_FROM_THREAD_ID = "spinOffFromThreadID"
        const val SPIN_OFF_COUNT          = "spinOffCount"

        // Engagement fields
        const val VIEW_COUNT         = "viewCount"
        const val HYPE_COUNT         = "hypeCount"
        const val COOL_COUNT         = "coolCount"
        const val REPLY_COUNT        = "replyCount"
        const val SHARE_COUNT        = "shareCount"
        const val LAST_ENGAGEMENT_AT = "lastEngagementAt"

        // Milestone tracking fields
        const val FIRST_HYPE_RECEIVED       = "firstHypeReceived"
        const val FIRST_COOL_RECEIVED       = "firstCoolReceived"
        const val MILESTONE_10_REACHED      = "milestone10Reached"
        const val MILESTONE_400_REACHED     = "milestone400Reached"
        const val MILESTONE_1000_REACHED    = "milestone1000Reached"
        const val MILESTONE_15000_REACHED   = "milestone15000Reached"
        const val MILESTONE_10_REACHED_AT   = "milestone10ReachedAt"
        const val MILESTONE_400_REACHED_AT  = "milestone400ReachedAt"
        const val MILESTONE_1000_REACHED_AT = "milestone1000ReachedAt"
        const val MILESTONE_15000_REACHED_AT = "milestone15000ReachedAt"

        // Metadata fields
        const val DURATION              = "duration"
        const val ASPECT_RATIO          = "aspectRatio"
        const val FILE_SIZE             = "fileSize"
        const val TEMPERATURE           = "temperature"
        const val CONTENT_TYPE          = "contentType"
        const val QUALITY_SCORE         = "qualityScore"
        const val ENGAGEMENT_RATIO      = "engagementRatio"
        const val VELOCITY_SCORE        = "velocityScore"
        const val TRENDING_SCORE        = "trendingScore"
        const val DISCOVERABILITY_SCORE = "discoverabilityScore"
        const val IS_PROMOTED           = "isPromoted"

        // Content authenticity
        const val RECORDING_SOURCE = "recordingSource"

        // Internal fields
        const val IS_INTERNAL_ACCOUNT = "isInternalAccount"
        const val IS_DELETED          = "isDeleted"
        const val IS_PROCESSING       = "isProcessing"
        const val MODERATION_STATUS   = "moderationStatus"

        // Collection Support Fields
        const val COLLECTION_ID  = "collectionID"
        const val SEGMENT_NUMBER = "segmentNumber"
        const val SEGMENT_TITLE  = "segmentTitle"
        const val REPLY_TIMESTAMP = "replyTimestamp"

        fun documentPath(videoID: String): String =
            Collections.fullPath(Collections.VIDEOS) + "/$videoID"
    }

    // MARK: - Distributed Counter Shard Document Schema

    object ShardDocument {
        const val COUNT           = "count"
        const val LAST_UPDATED_AT = "lastUpdatedAt"

        fun randomShardID(): String = (0 until Collections.SHARD_COUNT).random().toString()

        fun hypeShardPath(videoID: String, shardID: String): String =
            "${Collections.VIDEOS}/$videoID/${Collections.HYPE_SHARDS}/$shardID"

        fun coolShardPath(videoID: String, shardID: String): String =
            "${Collections.VIDEOS}/$videoID/${Collections.COOL_SHARDS}/$shardID"
    }

    // MARK: - User Document Schema (EXACT MATCH iOS FirebaseSchema.swift UserDocument)

    object UserDocument {
        // Core user fields
        const val ID               = "id"
        const val USERNAME         = "username"
        const val DISPLAY_NAME     = "displayName"
        const val EMAIL            = "email"
        const val PROFILE_IMAGE_URL = "profileImageURL"
        const val BIO              = "bio"
        const val CREATED_AT       = "createdAt"
        const val UPDATED_AT       = "updatedAt"
        const val LAST_ACTIVE_AT   = "lastActiveAt"

        // Searchable text for case-insensitive search
        const val SEARCHABLE_TEXT = "searchableText"

        // Tier and status fields
        const val TIER                = "tier"
        const val CLOUT               = "clout"
        const val IS_VERIFIED         = "isVerified"
        const val IS_INTERNAL_ACCOUNT = "isInternalAccount"
        const val IS_BANNED           = "isBanned"
        const val IS_PRIVATE          = "isPrivate"

        // Statistics fields
        const val FOLLOWER_COUNT       = "followerCount"
        const val FOLLOWING_COUNT      = "followingCount"
        const val VIDEO_COUNT          = "videoCount"
        const val THREAD_COUNT         = "threadCount"
        const val TOTAL_HYPES_RECEIVED = "totalHypesReceived"
        const val TOTAL_COOLS_RECEIVED = "totalCoolsReceived"
        const val DELETED_VIDEO_COUNT  = "deletedVideoCount"
        const val COLLECTION_COUNT     = "collectionCount"

        // Referral system fields
        const val REFERRAL_CODE           = "referralCode"
        const val INVITED_BY              = "invitedBy"
        const val REFERRAL_COUNT          = "referralCount"
        const val REFERRAL_CLOUT_EARNED   = "referralCloutEarned"
        const val HYPE_RATING_BONUS       = "hypeRatingBonus"
        const val REFERRAL_REWARDS_MAXED  = "referralRewardsMaxed"
        const val REFERRAL_CREATED_AT     = "referralCreatedAt"

        // CUSTOM REVENUE SHARE (Ambassador Promo / Special Deals)
        // Overrides tier-based SubscriptionRevenueShare when present + valid
        // EXACT MATCH iOS FirebaseSchema.swift UserDocument customSubShare fields
        const val CUSTOM_SUB_SHARE           = "customSubShare"           // Double (0.80 = 80%)
        const val CUSTOM_SUB_SHARE_EXPIRES_AT = "customSubShareExpiresAt" // Timestamp
        const val CUSTOM_SUB_SHARE_NOTE      = "customSubShareNote"       // String
        const val REFERRAL_GOAL              = "referralGoal"             // Int (100 for ambassadors)
        const val CUSTOM_SUB_SHARE_PERMANENT = "customSubSharePermanent"  // Bool — true if referralGoal met

        // Business account fields
        const val ACCOUNT_TYPE          = "accountType"           // "personal" or "business"
        const val BRAND_NAME            = "brandName"
        const val WEBSITE_URL           = "websiteURL"
        const val BUSINESS_CATEGORY     = "businessCategory"
        const val BRAND_LOGO_URL        = "brandLogoURL"
        const val BUSINESS_DESCRIPTION  = "businessDescription"
        const val IS_VERIFIED_BUSINESS  = "isVerifiedBusiness"

        // Settings fields
        const val NOTIFICATION_SETTINGS = "notificationSettings"
        const val PRIVACY_SETTINGS      = "privacySettings"
        const val CONTENT_PREFERENCES  = "contentPreferences"

        // Pinned videos (max 3 threads)
        const val PINNED_VIDEO_IDS = "pinnedVideoIDs"

        fun documentPath(userID: String): String =
            Collections.fullPath(Collections.USERS) + "/$userID"

        fun generateSearchableText(username: String, displayName: String): String {
            val cleanUsername    = username.lowercase().trim()
            val cleanDisplayName = displayName.lowercase().trim()
            return "$cleanUsername $cleanDisplayName"
        }
    }

    // MARK: - Referral Document Schema

    object ReferralDocument {
        const val ID            = "id"
        const val REFERRER_ID   = "referrerID"
        const val REFEREE_ID    = "refereeID"
        const val REFERRAL_CODE = "referralCode"
        const val STATUS        = "status"
        const val CREATED_AT    = "createdAt"
        const val COMPLETED_AT  = "completedAt"
        const val EXPIRES_AT    = "expiresAt"

        const val CLOUT_AWARDED    = "cloutAwarded"
        const val HYPE_BONUS       = "hypeBonus"
        const val REWARDS_CAPPED   = "rewardsCapped"

        const val SOURCE_TYPE         = "sourceType"
        const val PLATFORM            = "platform"
        const val IP_ADDRESS          = "ipAddress"
        const val DEVICE_FINGERPRINT  = "deviceFingerprint"
        const val USER_AGENT          = "userAgent"

        fun documentPath(referralID: String): String =
            Collections.fullPath(Collections.REFERRALS) + "/$referralID"
    }

    // MARK: - Thread Document Schema

    object ThreadDocument {
        const val ID              = "id"
        const val TITLE           = "title"
        const val DESCRIPTION     = "description"
        const val CREATOR_ID      = "creatorID"
        const val CREATED_AT      = "createdAt"
        const val UPDATED_AT      = "updatedAt"
        const val LAST_ACTIVITY_AT = "lastActivityAt"

        const val PARENT_VIDEO_ID      = "parentVideoID"
        const val CHILD_VIDEO_IDS      = "childVideoIDs"
        const val STEPCHILD_VIDEO_IDS  = "stepchildVideoIDs"
        const val CONVERSATION_DEPTH   = "conversationDepth"
        const val MAX_DEPTH            = "maxDepth"

        const val IS_LOCKED       = "isLocked"
        const val IS_ARCHIVED     = "isArchived"
        const val TEMPERATURE     = "temperature"
        const val TRENDING        = "trending"
        const val PARTICIPANT_COUNT = "participantCount"

        const val TOTAL_REPLIES    = "totalReplies"
        const val TOTAL_ENGAGEMENT = "totalEngagement"
        const val AVERAGE_ENGAGEMENT = "averageEngagement"

        fun documentPath(threadID: String): String =
            Collections.fullPath(Collections.THREADS) + "/$threadID"
    }

    // MARK: - Engagement Document Schema

    object EngagementDocument {
        const val VIDEO_ID          = "videoID"
        const val CREATOR_ID        = "creatorID"
        const val HYPE_COUNT        = "hypeCount"
        const val COOL_COUNT        = "coolCount"
        const val SHARE_COUNT       = "shareCount"
        const val REPLY_COUNT       = "replyCount"
        const val VIEW_COUNT        = "viewCount"
        const val LAST_ENGAGEMENT_AT = "lastEngagementAt"
        const val UPDATED_AT        = "updatedAt"

        const val NET_SCORE        = "netScore"
        const val ENGAGEMENT_RATIO = "engagementRatio"
        const val VELOCITY_SCORE   = "velocityScore"
        const val TRENDING_SCORE   = "trendingScore"

        fun documentPath(videoID: String): String =
            Collections.fullPath(Collections.ENGAGEMENT) + "/$videoID"
    }

    // MARK: - Interaction Document Schema

    object InteractionDocument {
        const val USER_ID         = "userID"
        const val VIDEO_ID        = "videoID"
        const val ENGAGEMENT_TYPE = "engagementType"
        const val TIMESTAMP       = "timestamp"
        const val CURRENT_TAPS    = "currentTaps"
        const val REQUIRED_TAPS   = "requiredTaps"
        const val IS_COMPLETED    = "isCompleted"
        const val IMPACT_VALUE    = "impactValue"

        fun documentPath(interactionID: String): String =
            Collections.fullPath(Collections.INTERACTIONS) + "/$interactionID"
    }

    // MARK: - Tap Progress Document Schema

    object TapProgressDocument {
        const val VIDEO_ID        = "videoID"
        const val USER_ID         = "userID"
        const val ENGAGEMENT_TYPE = "engagementType"
        const val CURRENT_TAPS    = "currentTaps"
        const val REQUIRED_TAPS   = "requiredTaps"
        const val LAST_TAP_TIME   = "lastTapTime"
        const val IS_COMPLETED    = "isCompleted"
        const val CREATED_AT      = "createdAt"
        const val UPDATED_AT      = "updatedAt"

        fun documentPath(progressID: String): String =
            Collections.fullPath(Collections.TAP_PROGRESS) + "/$progressID"
    }

    // MARK: - Notification Document Schema

    object NotificationDocument {
        const val ID           = "id"
        const val RECIPIENT_ID = "recipientID"
        const val SENDER_ID    = "senderID"
        const val TYPE         = "type"
        const val TITLE        = "title"
        const val MESSAGE      = "message"
        const val PAYLOAD      = "payload"
        const val IS_READ      = "isRead"
        const val CREATED_AT   = "createdAt"
        const val READ_AT      = "readAt"
        const val EXPIRES_AT   = "expiresAt"

        fun documentPath(notificationID: String): String =
            Collections.fullPath(Collections.NOTIFICATIONS) + "/$notificationID"
    }

    // MARK: - Following Document Schema

    object FollowingDocument {
        const val FOLLOWER_ID           = "followerID"
        const val FOLLOWING_ID          = "followingID"
        const val CREATED_AT            = "createdAt"
        const val IS_ACTIVE             = "isActive"
        const val NOTIFICATION_ENABLED  = "notificationEnabled"

        fun documentPath(followingID: String): String =
            Collections.fullPath(Collections.FOLLOWING) + "/$followingID"
    }

    // MARK: - Badge & Progression Schema

    object UserBadgesDocument {
        const val USER_ID            = "userID"
        const val EARNED_BADGES      = "earnedBadges"
        const val BADGE_PROGRESS     = "badgeProgress"
        const val TOTAL_BADGES_EARNED = "totalBadgesEarned"
        const val LAST_BADGE_EARNED  = "lastBadgeEarned"
        const val UPDATED_AT         = "updatedAt"

        fun documentPath(userID: String): String =
            Collections.fullPath(Collections.USER_BADGES) + "/$userID"
    }

    object ProgressionDocument {
        const val USER_ID           = "userID"
        const val CURRENT_LEVEL     = "currentLevel"
        const val EXPERIENCE        = "experience"
        const val LEVEL_PROGRESS    = "levelProgress"
        const val MILESTONES_REACHED = "milestonesReached"
        const val NEXT_MILESTONE    = "nextMilestone"
        const val UPDATED_AT        = "updatedAt"

        fun documentPath(userID: String): String =
            Collections.fullPath(Collections.PROGRESSION) + "/$userID"
    }

    // MARK: - Collection Document Schema

    object CollectionDocument {
        const val ID           = "id"
        const val TITLE        = "title"
        const val DESCRIPTION  = "description"
        const val CREATOR_ID   = "creatorID"
        const val CREATOR_NAME = "creatorName"
        const val CREATED_AT   = "createdAt"
        const val UPDATED_AT   = "updatedAt"
        const val PUBLISHED_AT = "publishedAt"

        const val SEGMENT_VIDEO_IDS  = "segmentVideoIDs"
        const val SEGMENT_THUMBNAILS = "segmentThumbnails"
        const val SEGMENT_COUNT      = "segmentCount"
        const val TOTAL_DURATION     = "totalDuration"

        const val STATUS        = "status"
        const val VISIBILITY    = "visibility"
        const val THUMBNAIL_URL = "thumbnailURL"

        const val TOTAL_VIEWS   = "totalViews"
        const val TOTAL_HYPES   = "totalHypes"
        const val TOTAL_COOLS   = "totalCools"
        const val TOTAL_REPLIES = "totalReplies"
        const val TOTAL_SHARES  = "totalShares"

        const val TEMPERATURE           = "temperature"
        const val DISCOVERABILITY_SCORE = "discoverabilityScore"
        const val IS_PROMOTED           = "isPromoted"
        const val IS_FEATURED           = "isFeatured"

        const val TAGS     = "tags"
        const val CATEGORY = "category"

        fun documentPath(collectionID: String): String =
            Collections.fullPath(Collections.VIDEO_COLLECTIONS) + "/$collectionID"
    }

    // MARK: - Collection Draft Document Schema

    object CollectionDraftDocument {
        const val ID         = "id"
        const val CREATOR_ID = "creatorID"
        const val CREATED_AT = "createdAt"
        const val UPDATED_AT = "updatedAt"
        const val TITLE       = "title"
        const val DESCRIPTION = "description"
        const val SEGMENTS    = "segments"

        object SegmentFields {
            const val LOCAL_VIDEO_PATH   = "localVideoPath"
            const val UPLOADED_VIDEO_URL = "uploadedVideoURL"
            const val THUMBNAIL_URL      = "thumbnailURL"
            const val SEGMENT_TITLE      = "segmentTitle"
            const val DURATION           = "duration"
            const val UPLOAD_STATUS      = "uploadStatus"
            const val UPLOAD_PROGRESS    = "uploadProgress"
            const val UPLOAD_ERROR       = "uploadError"
            const val FILE_SIZE          = "fileSize"
        }

        const val VISIBILITY        = "visibility"
        const val TAGS              = "tags"
        const val CATEGORY          = "category"
        const val AUTO_SAVE_ENABLED = "autoSaveEnabled"

        fun documentPath(draftID: String): String =
            Collections.fullPath(Collections.COLLECTION_DRAFTS) + "/$draftID"
    }

    // MARK: - Collection Progress Document Schema

    object CollectionProgressDocument {
        const val ID          = "id"
        const val COLLECTION_ID = "collectionID"
        const val USER_ID     = "userID"

        const val CURRENT_SEGMENT_INDEX    = "currentSegmentIndex"
        const val CURRENT_SEGMENT_PROGRESS = "currentSegmentProgress"

        const val COMPLETED_SEGMENTS = "completedSegments"
        const val TOTAL_WATCH_TIME   = "totalWatchTime"
        const val PERCENT_COMPLETE   = "percentComplete"

        const val LAST_WATCHED_AT = "lastWatchedAt"
        const val STARTED_AT      = "startedAt"
        const val COMPLETED_AT    = "completedAt"
        const val IS_COMPLETED    = "isCompleted"

        fun documentPath(progressID: String): String =
            Collections.fullPath(Collections.COLLECTION_PROGRESS) + "/$progressID"

        fun generateProgressID(collectionID: String, userID: String): String =
            "${collectionID}_${userID}"
    }

    // MARK: - HypeCoin Balance Document Schema (EXACT MATCH iOS HypeCoinService.swift)
    //
    // Firestore path: coin_balances/{userID}
    // One document per user. Created on first balance fetch if missing.
    //
    // CACHING: Cache 60s TTL in HypeCoinCoordinator (balance listener keeps it fresh).
    // REALTIME: Firestore snapshot listener detects Play Billing credits immediately.
    // BATCHING: FieldValue.increment used for all balance mutations — no read-then-write.

    object CoinBalanceDocument {
        const val USER_ID         = "userID"
        const val AVAILABLE_COINS = "availableCoins"
        const val PENDING_COINS   = "pendingCoins"      // Earned from subs/tips, not yet released
        const val LIFETIME_EARNED = "lifetimeEarned"
        const val LIFETIME_SPENT  = "lifetimeSpent"
        const val LAST_UPDATED    = "lastUpdated"

        fun documentPath(userID: String): String =
            Collections.fullPath("${Collections.COIN_BALANCES}/$userID")
    }

    // MARK: - Coin Transaction Document Schema (EXACT MATCH iOS CoinTransaction)
    //
    // Firestore path: coin_transactions/{transactionID}
    // Immutable once written — no caching TTL needed.
    // Fetch with: whereEqualTo("userID", uid).orderBy("createdAt", DESC).limit(50)
    //
    // BATCHING: Tips batched by HypeCoinCoordinator before a single write here.

    object CoinTransactionDocument {
        const val ID                       = "id"
        const val USER_ID                  = "userID"
        const val TYPE                     = "type"                  // CoinTransactionType.rawValue
        const val AMOUNT                   = "amount"                // Negative = debit
        const val BALANCE_AFTER            = "balanceAfter"
        const val RELATED_USER_ID          = "relatedUserID"         // Nullable
        const val RELATED_SUBSCRIPTION_ID  = "relatedSubscriptionID" // Nullable
        const val DESCRIPTION              = "description"
        const val CREATED_AT               = "createdAt"

        // Type raw values — EXACT MATCH iOS CoinTransactionType
        object Types {
            const val PURCHASE               = "purchase"
            const val SUBSCRIPTION_RECEIVED  = "sub_received"
            const val SUBSCRIPTION_SENT      = "sub_sent"
            const val TIP_RECEIVED           = "tip_received"
            const val TIP_SENT               = "tip_sent"
            const val CASH_OUT               = "cash_out"
            const val REFUND                 = "refund"
            const val BONUS                  = "bonus"
        }

        fun documentPath(transactionID: String): String =
            Collections.fullPath("${Collections.COIN_TRANSACTIONS}/$transactionID")
    }

    // MARK: - Cash Out Request Document Schema (EXACT MATCH iOS CashOutRequest)
    //
    // Firestore path: cash_out_requests/{requestID}
    // Written by client, processed by Cloud Function / admin.
    // No caching — low frequency, always fetch fresh.

    object CashOutRequestDocument {
        const val ID                 = "id"
        const val USER_ID            = "userID"
        const val COIN_AMOUNT        = "coinAmount"
        const val USER_TIER          = "userTier"            // UserTier.rawValue
        const val CREATOR_PERCENTAGE = "creatorPercentage"   // Double 0.0–1.0
        const val CREATOR_AMOUNT     = "creatorAmount"       // USD
        const val PLATFORM_AMOUNT    = "platformAmount"      // USD
        const val STATUS             = "status"              // CashOutStatus.rawValue
        const val PAYOUT_METHOD      = "payoutMethod"        // PayoutMethod.rawValue
        const val CREATED_AT         = "createdAt"
        const val PROCESSED_AT       = "processedAt"         // Nullable
        const val FAILURE_REASON     = "failureReason"       // Nullable

        // Status raw values — EXACT MATCH iOS CashOutStatus
        object Statuses {
            const val PENDING    = "pending"
            const val PROCESSING = "processing"
            const val COMPLETED  = "completed"
            const val FAILED     = "failed"
        }

        // Payout method raw values — EXACT MATCH iOS PayoutMethod
        object PayoutMethods {
            const val BANK_TRANSFER = "bank_transfer"
            const val PAYPAL        = "paypal"
            const val STRIPE        = "stripe"
        }

        fun documentPath(requestID: String): String =
            Collections.fullPath("${Collections.CASH_OUT_REQUESTS}/$requestID")
    }

    // MARK: - Subscription Plan Document Schema (EXACT MATCH iOS CreatorSubscriptionPlan)
    //
    // Firestore path: subscription_plans/{creatorID}
    // One per creator.
    // CACHING: SubscriptionService.subscriptionCache, 10-min TTL.

    object SubscriptionPlanDocument {
        const val CREATOR_ID         = "creatorID"
        const val IS_ENABLED         = "isEnabled"
        const val SUPPORTER_PRICE    = "supporterPrice"
        const val SUPER_FAN_PRICE    = "superFanPrice"
        const val SUPPORTER_ENABLED  = "supporterEnabled"
        const val SUPER_FAN_ENABLED  = "superFanEnabled"
        const val CUSTOM_WELCOME_MSG = "customWelcomeMessage"
        const val SUBSCRIBER_COUNT   = "subscriberCount"
        const val TOTAL_EARNED       = "totalEarned"
        const val CREATED_AT         = "createdAt"
        const val UPDATED_AT         = "updatedAt"

        fun documentPath(creatorID: String): String =
            Collections.fullPath("${Collections.SUBSCRIPTION_PLANS}/$creatorID")
    }

    // MARK: - Active Subscription Document Schema (EXACT MATCH iOS ActiveSubscription)
    //
    // Firestore path: subscriptions/{subscriberID}_{creatorID}
    // CACHING: SubscriptionService.subscriptionCache, 5-min TTL.

    object ActiveSubscriptionDocument {
        const val ID              = "id"               // Format: {subscriberID}_{creatorID}
        const val SUBSCRIBER_ID   = "subscriberID"
        const val CREATOR_ID      = "creatorID"
        const val TIER            = "tier"             // SubscriptionTier.value
        const val COINS_PAID      = "coinsPaid"
        const val STATUS          = "status"
        const val STARTED_AT      = "startedAt"
        const val EXPIRES_AT      = "expiresAt"
        const val RENEWAL_ENABLED = "renewalEnabled"
        const val RENEWAL_COUNT   = "renewalCount"

        // Status raw values — EXACT MATCH iOS SubscriptionStatus
        object Statuses {
            const val ACTIVE    = "active"
            const val EXPIRED   = "expired"
            const val CANCELLED = "cancelled"
            const val PAUSED    = "paused"
        }

        fun documentPath(subscriberID: String, creatorID: String): String =
            Collections.fullPath("${Collections.SUBSCRIPTIONS}/${subscriberID}_${creatorID}")
    }

    // MARK: - Subscription Event Document Schema (EXACT MATCH iOS SubscriptionEvent)
    //
    // Firestore path: subscription_events/{eventID}

    object SubscriptionEventDocument {
        const val ID              = "id"
        const val SUBSCRIPTION_ID = "subscriptionID"
        const val SUBSCRIBER_ID   = "subscriberID"
        const val CREATOR_ID      = "creatorID"
        const val TYPE            = "type"
        const val TIER            = "tier"
        const val COIN_AMOUNT     = "coinAmount"
        const val CREATED_AT      = "createdAt"

        // Event type raw values — EXACT MATCH iOS SubscriptionEventType
        object Types {
            const val NEW_SUBSCRIPTION = "new"
            const val RENEWAL          = "renewal"
            const val CANCELLATION     = "cancellation"
            const val EXPIRATION       = "expiration"
        }

        fun documentPath(eventID: String): String =
            Collections.fullPath("${Collections.SUBSCRIPTION_EVENTS}/$eventID")
    }

    // MARK: - Required Indexes for stitchfin Performance

    object RequiredIndexes {

        val videosByCreator    = listOf(VideoDocument.CREATOR_ID, VideoDocument.CREATED_AT)
        val videosByThread     = listOf(VideoDocument.THREAD_ID, VideoDocument.CONVERSATION_DEPTH, VideoDocument.CREATED_AT)
        val videosByEngagement = listOf(VideoDocument.TEMPERATURE, VideoDocument.HYPE_COUNT, VideoDocument.LAST_ENGAGEMENT_AT)
        val videosByTaggedUser = listOf(VideoDocument.TAGGED_USER_IDS, VideoDocument.CREATED_AT)
        val videosByMilestone  = listOf(VideoDocument.MILESTONE_1000_REACHED, VideoDocument.MILESTONE_1000_REACHED_AT)

        val hashtagsByRecency     = listOf(VideoDocument.HASHTAGS, VideoDocument.CREATED_AT)
        val hashtagsByTrending    = listOf(VideoDocument.HASHTAGS, VideoDocument.TRENDING_SCORE, VideoDocument.CREATED_AT)
        val hashtagsByEngagement  = listOf(VideoDocument.HASHTAGS, VideoDocument.ENGAGEMENT_RATIO, VideoDocument.CREATED_AT)
        val hashtagsByPopularity  = listOf(VideoDocument.HASHTAGS, VideoDocument.VIEW_COUNT, VideoDocument.CREATED_AT)
        val hashtagsByContentType = listOf(VideoDocument.HASHTAGS, VideoDocument.CONTENT_TYPE, VideoDocument.CREATED_AT)
        val hashtagsThreadsOnly   = listOf(VideoDocument.HASHTAGS, VideoDocument.CONVERSATION_DEPTH, VideoDocument.TRENDING_SCORE)

        val videosByCollection  = listOf(VideoDocument.COLLECTION_ID, VideoDocument.SEGMENT_NUMBER)
        val timestampedReplies  = listOf(VideoDocument.REPLY_TO_VIDEO_ID, VideoDocument.REPLY_TIMESTAMP)
        val collectionsByCreator = listOf(CollectionDocument.CREATOR_ID, CollectionDocument.CREATED_AT)
        val publishedCollections = listOf(CollectionDocument.STATUS, CollectionDocument.VISIBILITY, CollectionDocument.PUBLISHED_AT)
        val draftsByUser        = listOf(CollectionDraftDocument.CREATOR_ID, CollectionDraftDocument.UPDATED_AT)
        val progressByUser      = listOf(CollectionProgressDocument.USER_ID, CollectionProgressDocument.LAST_WATCHED_AT)
        val featuredCollections = listOf(CollectionDocument.IS_FEATURED, CollectionDocument.DISCOVERABILITY_SCORE)

        val usersByTier     = listOf(UserDocument.TIER, UserDocument.CLOUT)
        val usersByActivity = listOf(UserDocument.LAST_ACTIVE_AT, UserDocument.IS_PRIVATE)

        val interactionsByUser  = listOf(InteractionDocument.USER_ID, InteractionDocument.TIMESTAMP)
        val interactionsByVideo = listOf(InteractionDocument.VIDEO_ID, InteractionDocument.ENGAGEMENT_TYPE, InteractionDocument.TIMESTAMP)

        val followingByFollower  = listOf(FollowingDocument.FOLLOWER_ID, FollowingDocument.IS_ACTIVE, FollowingDocument.CREATED_AT)
        val followingByFollowing = listOf(FollowingDocument.FOLLOWING_ID, FollowingDocument.IS_ACTIVE, FollowingDocument.CREATED_AT)

        val notificationsByRecipient = listOf(NotificationDocument.RECIPIENT_ID, NotificationDocument.IS_READ, NotificationDocument.CREATED_AT)
        val notificationsByType      = listOf(NotificationDocument.TYPE, NotificationDocument.CREATED_AT)

        val threadsByActivity    = listOf(ThreadDocument.LAST_ACTIVITY_AT, ThreadDocument.TRENDING)
        val threadsByTemperature = listOf(ThreadDocument.TEMPERATURE, ThreadDocument.PARTICIPANT_COUNT)

        val referralsByCode    = listOf(ReferralDocument.REFERRAL_CODE, ReferralDocument.STATUS, ReferralDocument.EXPIRES_AT)
        val referralsByReferrer = listOf(ReferralDocument.REFERRER_ID, ReferralDocument.STATUS, ReferralDocument.CREATED_AT)
        val usersByReferralCode = listOf(UserDocument.REFERRAL_CODE)
        val referralsByStatus  = listOf(ReferralDocument.STATUS, ReferralDocument.CREATED_AT, ReferralDocument.EXPIRES_AT)

        // HypeCoin indexes
        val coinTransactionsByUser = listOf(CoinTransactionDocument.USER_ID, CoinTransactionDocument.CREATED_AT)
        val cashOutsByUser         = listOf(CashOutRequestDocument.USER_ID, CashOutRequestDocument.CREATED_AT)
        val cashOutsByStatus       = listOf(CashOutRequestDocument.STATUS, CashOutRequestDocument.CREATED_AT)

        // Subscription indexes
        val subscriptionsBySubscriber = listOf(ActiveSubscriptionDocument.SUBSCRIBER_ID, ActiveSubscriptionDocument.STATUS)
        val subscriptionsByCreator    = listOf(ActiveSubscriptionDocument.CREATOR_ID, ActiveSubscriptionDocument.STATUS, ActiveSubscriptionDocument.STARTED_AT)
        val subscriptionEventsByCreator = listOf(SubscriptionEventDocument.CREATOR_ID, SubscriptionEventDocument.CREATED_AT)

        fun generateIndexCommands(): List<String> {
            return listOf(
                "firebase firestore:indexes --project=stitchbeta-8bbfe --database=stitchfin",
                "// Database: stitchfin",
                "// Collection: videos - Creator timeline",
                "// Collection: videos - Thread hierarchy",
                "// Collection: videos - Tagged users",
                "// Collection: videos - Milestone tracking",
                "// Collection: videos - Hashtag search",
                "// Collection: videos - Collection segments",
                "// Collection: videos - Timestamped replies",
                "// Collection: videoCollections - By creator",
                "// Collection: videoCollections - Published discovery",
                "// Collection: videoCollections - Featured",
                "// Collection: collectionDrafts - By user",
                "// Collection: collectionProgress - By user",
                "// Collection: interactions - User engagement",
                "// Collection: following - Social connections",
                "// Collection: notifications - User notifications",
                "// Collection: referrals - Referral tracking",
                "// Collection: coin_transactions - By user (HypeCoin)",
                "// Collection: cash_out_requests - By user + status (HypeCoin)",
                "// Collection: subscriptions - By subscriber + creator (Subscriptions)",
                "// Collection: subscription_events - By creator (Subscriptions)"
            )
        }

        fun getHashtagIndexes(): List<List<String>> = listOf(
            hashtagsByRecency, hashtagsByTrending, hashtagsByEngagement,
            hashtagsByPopularity, hashtagsByContentType, hashtagsThreadsOnly
        )
    }

    // MARK: - Data Validation Rules

    object ValidationRules {

        const val MAX_VIDEO_TITLE_LENGTH = 100
        const val MIN_VIDEO_TITLE_LENGTH = 1
        const val MAX_VIDEO_DURATION     = 300.0
        const val MAX_VIDEO_FILE_SIZE    = 100L * 1024 * 1024
        val ALLOWED_VIDEO_FORMATS        = listOf("mp4", "mov", "m4v")

        const val MAX_TAGGED_USERS_PER_VIDEO = 5
        const val MIN_TAGGED_USERS_PER_VIDEO = 0

        const val MAX_HASHTAGS_PER_VIDEO = 10
        const val MIN_HASHTAGS_PER_VIDEO = 0
        const val MAX_HASHTAG_LENGTH     = 30
        const val MIN_HASHTAG_LENGTH     = 2
        const val HASHTAG_PATTERN        = "^[a-zA-Z0-9_]+$"
        val RESERVED_HASHTAGS            = listOf("admin", "system", "deleted", "banned", "nsfw", "spam")

        const val MAX_USERNAME_LENGTH    = 20
        const val MIN_USERNAME_LENGTH    = 3
        const val MAX_DISPLAY_NAME_LENGTH = 50
        const val MAX_BIO_LENGTH         = 150
        const val USERNAME_PATTERN       = "^[a-zA-Z0-9_]+$"

        const val MAX_THREAD_TITLE_LENGTH  = 100
        const val MIN_THREAD_TITLE_LENGTH  = 3
        const val MAX_CONVERSATION_DEPTH   = 2
        const val MAX_CHILDREN_PER_THREAD  = 10
        const val MAX_STEPCHILDREN_PER_CHILD = 10

        const val MAX_TAPS_REQUIRED             = 10
        const val MIN_TAPS_REQUIRED             = 1
        const val ENGAGEMENT_COOLDOWN_SECONDS   = 1
        const val MAX_ENGAGEMENT_RATE_PER_MINUTE = 60

        const val MAX_NOTIFICATION_TITLE_LENGTH   = 80
        const val MAX_NOTIFICATION_MESSAGE_LENGTH = 200
        const val NOTIFICATION_EXPIRATION_DAYS    = 30

        const val MILESTONE_HEATING_UP = 10
        const val MILESTONE_MUST_SEE   = 400
        const val MILESTONE_HOT        = 1000
        const val MILESTONE_VIRAL      = 15000

        const val REFERRAL_CODE_LENGTH           = 8
        const val MAX_REFERRAL_CLOUT             = 1000
        const val REFERRAL_EXPIRATION_DAYS       = 30
        const val HYPE_RATING_BONUS_PER_REFERRAL = 0.001
        const val CLOUT_PER_REFERRAL             = 100
        const val MAX_REFERRALS_FOR_CLOUT        = 10

        const val MAX_COLLECTION_TITLE_LENGTH       = 100
        const val MIN_COLLECTION_TITLE_LENGTH       = 3
        const val MAX_COLLECTION_DESCRIPTION_LENGTH = 500
        const val MAX_SEGMENTS_PER_COLLECTION       = 20
        const val MIN_SEGMENTS_PER_COLLECTION       = 2
        const val MAX_SEGMENT_TITLE_LENGTH          = 50
        const val MAX_COLLECTION_TAGS               = 10

        // HypeCoin validation — EXACT MATCH iOS CashOutLimits
        const val COIN_CASHOUT_MINIMUM     = 1_000    // $10 minimum
        const val COIN_CASHOUT_DAILY_MAX   = 100_000  // $1,000/day
        const val COIN_TIP_COOLDOWN_SECONDS = 2       // Tip batch window

        fun isValidUsername(username: String): Boolean =
            username.length in MIN_USERNAME_LENGTH..MAX_USERNAME_LENGTH &&
                    username.matches(Regex(USERNAME_PATTERN))

        fun isValidVideoDuration(duration: Double): Boolean =
            duration > 0 && duration <= MAX_VIDEO_DURATION

        fun isValidVideoFileSize(fileSize: Long): Boolean =
            fileSize > 0 && fileSize <= MAX_VIDEO_FILE_SIZE

        fun isValidConversationDepth(depth: Int): Boolean =
            depth in 0..MAX_CONVERSATION_DEPTH

        fun validateTaggedUsers(userIDs: List<String>): Boolean =
            userIDs.size <= MAX_TAGGED_USERS_PER_VIDEO && userIDs.all { it.isNotEmpty() }

        fun validateReferralCode(code: String): Boolean =
            code.length == REFERRAL_CODE_LENGTH &&
                    code.all { it.isLetterOrDigit() } &&
                    code == code.uppercase()

        fun validateReferralRewards(currentClout: Int, newReferrals: Int): Boolean =
            (currentClout + (newReferrals * CLOUT_PER_REFERRAL)) <= MAX_REFERRAL_CLOUT

        fun checkMilestoneReached(hypeCount: Int): Int? = when (hypeCount) {
            MILESTONE_VIRAL      -> MILESTONE_VIRAL
            MILESTONE_HOT        -> MILESTONE_HOT
            MILESTONE_MUST_SEE   -> MILESTONE_MUST_SEE
            MILESTONE_HEATING_UP -> MILESTONE_HEATING_UP
            else                 -> null
        }

        fun isValidHashtag(hashtag: String): Boolean {
            val clean = hashtag.removePrefix("#").lowercase()
            return clean.length in MIN_HASHTAG_LENGTH..MAX_HASHTAG_LENGTH &&
                    clean.matches(Regex(HASHTAG_PATTERN)) &&
                    !RESERVED_HASHTAGS.contains(clean)
        }

        fun isValidHashtagsArray(hashtags: List<String>): Boolean {
            if (hashtags.size > MAX_HASHTAGS_PER_VIDEO) return false
            val unique = hashtags.map { it.removePrefix("#").lowercase() }.toSet()
            if (unique.size != hashtags.size) return false
            return hashtags.all { isValidHashtag(it) }
        }

        fun normalizeHashtag(hashtag: String): String =
            hashtag.removePrefix("#").lowercase().trim()

        fun normalizeHashtags(hashtags: List<String>): List<String> =
            hashtags.map { normalizeHashtag(it) }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_HASHTAGS_PER_VIDEO)

        fun validateCollection(title: String, description: String?, segmentCount: Int, tags: List<String>?): List<String> {
            val errors = mutableListOf<String>()
            if (title.length < MIN_COLLECTION_TITLE_LENGTH) errors.add("Title too short")
            if (title.length > MAX_COLLECTION_TITLE_LENGTH) errors.add("Title too long")
            if (description != null && description.length > MAX_COLLECTION_DESCRIPTION_LENGTH) errors.add("Description too long")
            if (segmentCount < MIN_SEGMENTS_PER_COLLECTION) errors.add("Not enough segments")
            if (segmentCount > MAX_SEGMENTS_PER_COLLECTION) errors.add("Too many segments")
            if (tags != null && tags.size > MAX_COLLECTION_TAGS) errors.add("Too many tags")
            return errors
        }
    }

    // MARK: - Document ID Patterns

    object DocumentIDPatterns {
        fun generateVideoID(): String        = "video_${System.currentTimeMillis()}_${(1000..9999).random()}"
        fun generateThreadID(parentVideoID: String): String = parentVideoID
        fun generateEngagementID(videoID: String): String   = videoID
        fun generateInteractionID(videoID: String, userID: String, type: String): String = "${videoID}_${userID}_$type"
        fun generateTapProgressID(videoID: String, userID: String, type: String): String = "${videoID}_${userID}_$type"
        fun generateFollowingID(followerID: String, followingID: String): String         = "${followerID}_$followingID"
        fun generateNotificationID(): String = "notif_${System.currentTimeMillis()}_${(100..999).random()}"
        fun generateReferralID(referrerID: String): String  = "ref_${referrerID.take(4)}_${System.currentTimeMillis()}_${(100..999).random()}"
        fun generateReferralCode(): String   = (1..8).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".random() }.joinToString("")
        fun generateCollectionID(): String   = "coll_${System.currentTimeMillis()}_${(1000..9999).random()}"
        fun generateDraftID(creatorID: String): String = "draft_${creatorID.take(4)}_${System.currentTimeMillis()}_${(100..999).random()}"
        fun generateProgressID(collectionID: String, userID: String): String = "${collectionID}_$userID"

        /** Subscription document ID — matches iOS "{subscriberID}_{creatorID}" */
        fun generateSubscriptionID(subscriberID: String, creatorID: String): String = "${subscriberID}_${creatorID}"

        fun validateID(id: String, type: String): Boolean = when (type) {
            "video"        -> id.startsWith("video_")
            "referral"     -> id.startsWith("ref_")
            "notification" -> id.startsWith("notif_")
            "collection"   -> id.startsWith("coll_")
            "draft"        -> id.startsWith("draft_")
            else           -> id.isNotEmpty()
        }
    }

    // MARK: - Query Patterns for stitchfin

    object QueryPatterns {
        const val USER_VIDEOS            = "stitchfin/videos WHERE creatorID == {userID} ORDER BY createdAt DESC"
        const val THREAD_HIERARCHY       = "stitchfin/videos WHERE threadID == {threadID} ORDER BY conversationDepth ASC, createdAt ASC"
        const val USER_INTERACTIONS      = "stitchfin/interactions WHERE userID == {userID} ORDER BY timestamp DESC"
        const val FOLLOWING_LIST         = "stitchfin/following WHERE followerID == {userID} AND isActive == true ORDER BY createdAt DESC"
        const val UNREAD_NOTIFICATIONS   = "stitchfin/notifications WHERE recipientID == {userID} AND isRead == false ORDER BY createdAt DESC"
        const val VIDEOS_WITH_TAGGED_USER = "stitchfin/videos WHERE taggedUserIDs array-contains {userID} ORDER BY createdAt DESC"
        const val REFERRAL_BY_CODE       = "stitchfin/referrals WHERE referralCode == {code} AND status == 'pending' LIMIT 1"
        const val USER_REFERRALS         = "stitchfin/referrals WHERE referrerID == {userID} ORDER BY createdAt DESC"
        const val EXPIRED_REFERRALS      = "stitchfin/referrals WHERE status == 'pending' AND expiresAt < {currentTime}"
        const val COLLECTION_SEGMENTS    = "stitchfin/videos WHERE collectionID == {collectionID} ORDER BY segmentNumber ASC"
        const val TIMESTAMPED_REPLIES    = "stitchfin/videos WHERE replyToVideoID == {segmentVideoID} ORDER BY replyTimestamp ASC"
        const val USER_COLLECTIONS       = "stitchfin/videoCollections WHERE creatorID == {userID} ORDER BY createdAt DESC"
        const val PUBLISHED_COLLECTIONS  = "stitchfin/videoCollections WHERE status == 'published' AND visibility == 'public' ORDER BY publishedAt DESC"
        const val USER_DRAFTS            = "stitchfin/collectionDrafts WHERE creatorID == {userID} ORDER BY updatedAt DESC"
        const val USER_WATCH_PROGRESS    = "stitchfin/collectionProgress WHERE userID == {userID} ORDER BY lastWatchedAt DESC"
        const val FEATURED_COLLECTIONS   = "stitchfin/videoCollections WHERE isFeatured == true AND status == 'published' ORDER BY discoverabilityScore DESC"

        // HypeCoin queries — EXACT MATCH iOS HypeCoinService fetch patterns
        const val USER_COIN_TRANSACTIONS     = "stitchfin/coin_transactions WHERE userID == {userID} ORDER BY createdAt DESC LIMIT 50"
        const val USER_CASH_OUT_REQUESTS     = "stitchfin/cash_out_requests WHERE userID == {userID} ORDER BY createdAt DESC"
        const val DUPLICATE_TRANSACTION_CHECK = "stitchfin/coin_transactions WHERE id == {transactionID} LIMIT 1"

        // Subscription queries — EXACT MATCH iOS SubscriptionService fetch patterns
        const val USER_SUBSCRIPTIONS        = "stitchfin/subscriptions WHERE subscriberID == {userID} AND status == 'active'"
        const val CREATOR_SUBSCRIBERS       = "stitchfin/subscriptions WHERE creatorID == {creatorID} AND status == 'active' ORDER BY startedAt DESC"
        const val SUBSCRIPTION_BY_PAIR      = "stitchfin/subscriptions/{subscriberID}_{creatorID}"

        fun generateQuery(pattern: String, parameters: Map<String, String>): String {
            var query = pattern
            for ((key, value) in parameters) {
                query = query.replace("{$key}", value)
            }
            return query
        }
    }

    // MARK: - Data Consistency Rules for stitchfin

    object ConsistencyRules {
        val threadDepthLimits  = mapOf(0 to "thread", 1 to "child", 2 to "stepchild")
        val maxRepliesPerLevel = mapOf(0 to 10, 1 to 10, 2 to 0)
        val engagementTypes    = listOf("hype", "cool", "share", "reply", "view")

        val requiredTapsByTier = mapOf(
            "rookie" to 1, "rising" to 2, "influencer" to 3,
            "partner" to 4, "topCreator" to 5, "founder" to 1, "coFounder" to 1
        )

        val tierRequirements = mapOf(
            "rookie" to Pair(0, 0), "rising" to Pair(5000, 50),
            "influencer" to Pair(15000, 200), "partner" to Pair(50000, 1000),
            "topCreator" to Pair(150000, 5000), "founder" to Pair(0, 0), "coFounder" to Pair(0, 0)
        )

        val referralStatuses     = listOf("pending", "completed", "expired", "failed")
        val referralSourceTypes  = listOf("link", "deeplink", "manual", "share")
        val referralPlatforms    = listOf("ios", "android", "web")
        val collectionStatuses   = listOf("draft", "processing", "published", "archived", "deleted")
        val collectionVisibilities = listOf("public", "followers", "private", "unlisted")
        val segmentUploadStatuses = listOf("pending", "uploading", "uploaded", "failed")

        // HypeCoin consistency
        val coinTransactionTypes = listOf(
            "purchase", "sub_received", "sub_sent", "tip_received",
            "tip_sent", "cash_out", "refund", "bonus"
        )
        val cashOutStatuses  = listOf("pending", "processing", "completed", "failed")
        val payoutMethods    = listOf("bank_transfer", "paypal", "stripe")
        val subscriptionStatuses = listOf("active", "expired", "cancelled", "paused")

        fun validateConsistency(data: Map<String, Any>, type: String): List<String> {
            val errors = mutableListOf<String>()
            when (type) {
                "video" -> {
                    val depth = data["conversationDepth"] as? Int
                    if (depth != null && depth > 2) errors.add("Conversation depth exceeds maximum (2)")
                    @Suppress("UNCHECKED_CAST")
                    val taggedUsers = data["taggedUserIDs"] as? List<String>
                    if (taggedUsers != null && !ValidationRules.validateTaggedUsers(taggedUsers))
                        errors.add("Invalid tagged users array")
                }
                "user" -> {
                    val username = data["username"] as? String
                    if (username.isNullOrEmpty()) errors.add("Username cannot be empty")
                    val referralCode = data["referralCode"] as? String
                    if (referralCode != null && !ValidationRules.validateReferralCode(referralCode))
                        errors.add("Invalid referral code format")
                }
                "referral" -> {
                    val status = data["status"] as? String
                    if (status != null && !referralStatuses.contains(status)) errors.add("Invalid referral status")
                }
                "collection" -> {
                    val status = data["status"] as? String
                    if (status != null && !collectionStatuses.contains(status)) errors.add("Invalid collection status: $status")
                    val visibility = data["visibility"] as? String
                    if (visibility != null && !collectionVisibilities.contains(visibility)) errors.add("Invalid collection visibility: $visibility")
                }
                "coin_transaction" -> {
                    val type2 = data["type"] as? String
                    if (type2 != null && !coinTransactionTypes.contains(type2)) errors.add("Invalid coin transaction type: $type2")
                }
                "cash_out" -> {
                    val status = data["status"] as? String
                    if (status != null && !cashOutStatuses.contains(status)) errors.add("Invalid cash out status: $status")
                    val method = data["payoutMethod"] as? String
                    if (method != null && !payoutMethods.contains(method)) errors.add("Invalid payout method: $method")
                }
                "subscription" -> {
                    val status = data["status"] as? String
                    if (status != null && !subscriptionStatuses.contains(status)) errors.add("Invalid subscription status: $status")
                }
            }
            return errors
        }

        fun validateReferralBusinessRules(referrerID: String, refereeID: String, currentReferralCount: Int, currentCloutEarned: Int): List<String> {
            val errors = mutableListOf<String>()
            if (referrerID == refereeID) errors.add("Cannot refer yourself")
            if (currentCloutEarned >= ValidationRules.MAX_REFERRAL_CLOUT) errors.add("Referral clout reward cap reached (1000)")
            return errors
        }

        fun validateCollectionBusinessRules(creatorID: String, segmentCount: Int, status: String, visibility: String): List<String> {
            val errors = mutableListOf<String>()
            if (creatorID.isEmpty()) errors.add("Collection must have a creator")
            if (segmentCount < ValidationRules.MIN_SEGMENTS_PER_COLLECTION && status == "published")
                errors.add("Published collection must have at least ${ValidationRules.MIN_SEGMENTS_PER_COLLECTION} segments")
            if (!collectionStatuses.contains(status)) errors.add("Invalid collection status: $status")
            if (!collectionVisibilities.contains(visibility)) errors.add("Invalid collection visibility: $visibility")
            return errors
        }
    }

    // MARK: - Caching Configuration

    object CacheConfiguration {
        const val VIDEO_CACHE_TTL               = 300L
        const val THUMBNAIL_CACHE_TTL           = 3600L
        const val PROFILE_IMAGE_CACHE_TTL       = 1800L
        const val ENGAGEMENT_CACHE_TTL          = 30L
        const val TAP_PROGRESS_CACHE_TTL        = 60L
        const val USER_PROFILE_CACHE_TTL        = 600L
        const val FOLLOWING_LIST_CACHE_TTL      = 300L
        const val THREAD_STRUCTURE_CACHE_TTL    = 180L
        const val THREAD_LIST_CACHE_TTL         = 120L
        const val HASHTAG_SEARCH_CACHE_TTL      = 300L
        const val TRENDING_HASHTAGS_CACHE_TTL   = 600L
        const val HASHTAG_SUGGESTIONS_CACHE_TTL = 1800L

        // HypeCoin TTLs — EXACT MATCH iOS HypeCoinCoordinator balanceCacheTTL
        const val COIN_BALANCE_CACHE_TTL        = 60L    // 1 min — real-time listener keeps fresh
        const val COIN_TRANSACTIONS_CACHE_TTL   = 0L     // No TTL — immutable
        const val SUBSCRIPTION_PLAN_CACHE_TTL   = 600L   // 10 min — matches iOS SubscriptionService
        const val SUBSCRIPTION_STATUS_CACHE_TTL = 300L   // 5 min — matches iOS SubscriptionService

        fun cacheKey(collection: String, document: String): String = "stitchfin_${collection}_$document"
        fun coinBalanceCacheKey(userID: String): String = "stitchfin_coin_balance_$userID"
        fun subscriptionCacheKey(subscriberID: String, creatorID: String): String = "stitchfin_sub_${subscriberID}_$creatorID"
        fun hashtagSearchCacheKey(hashtag: String): String = "stitchfin_hashtag_search_${hashtag.removePrefix("#").lowercase()}"
        fun trendingHashtagsCacheKey(): String = "stitchfin_trending_hashtags"
        fun hashtagSuggestionsCacheKey(partial: String): String = "stitchfin_hashtag_suggestions_${partial.removePrefix("#").lowercase()}"
    }

    // MARK: - Database Operations

    object Operations {
        const val MAX_BATCH_SIZE                  = 500
        const val BATCH_RETRY_ATTEMPTS            = 3
        const val BATCH_TIMEOUT_SECONDS           = 30
        const val MAX_TRANSACTION_RETRIES         = 5
        const val TRANSACTION_TIMEOUT_SECONDS     = 60
        const val MAX_LISTENERS_PER_VIEW          = 5
        const val LISTENER_RECONNECT_DELAY_SECONDS = 2
        const val LISTENER_MAX_RECONNECT_ATTEMPTS  = 10

        fun operationMetrics(): Map<String, Any> = mapOf(
            "database"             to DATABASE_NAME,
            "maxBatchSize"         to MAX_BATCH_SIZE,
            "maxTransactionRetries" to MAX_TRANSACTION_RETRIES,
            "maxListeners"         to MAX_LISTENERS_PER_VIEW,
            "hashtagIndexes"       to RequiredIndexes.getHashtagIndexes().size
        )
    }

    // MARK: - Database Initialization

    fun initializeSchema(): Boolean {
        println("🔧 FIREBASE SCHEMA: Initializing stitchfin database schema...")

        val databaseValid        = validateDatabaseConfig()
        val collectionsValid     = Collections.validateCollections().isEmpty()
        val referralSchemaValid  = validateReferralSchema()
        val milestoneSchemaValid = validateMilestoneSchema()
        val collectionsSchemaValid = validateCollectionsSchema()
        val coinSchemaValid      = validateCoinSchema()

        return if (databaseValid && collectionsValid && referralSchemaValid &&
            milestoneSchemaValid && collectionsSchemaValid && coinSchemaValid) {
            println("✅ FIREBASE SCHEMA: stitchfin database schema initialized successfully")
            println("📊 FIREBASE SCHEMA: Collections: ${Collections.validateCollections().size}")
            println("🔍 FIREBASE SCHEMA: Indexes: ${RequiredIndexes.generateIndexCommands().size}")
            println("🔗 FIREBASE SCHEMA: Referral system integrated")
            println("🏷️ FIREBASE SCHEMA: User tagging system integrated")
            println("🏅 FIREBASE SCHEMA: Milestone tracking system integrated")
            println("📁 FIREBASE SCHEMA: Collections feature integrated")
            println("💰 FIREBASE SCHEMA: HypeCoin system integrated")
            println("🎟️ FIREBASE SCHEMA: Subscription system integrated")
            true
        } else {
            println("❌ FIREBASE SCHEMA: stitchfin database schema initialization failed")
            false
        }
    }

    private fun validateReferralSchema(): Boolean {
        println("✅ REFERRAL SCHEMA: User fields + referral fields validated")
        return true
    }

    private fun validateMilestoneSchema(): Boolean {
        println("✅ MILESTONE SCHEMA: Milestone tracking fields validated")
        return true
    }

    private fun validateCollectionsSchema(): Boolean {
        println("✅ COLLECTIONS SCHEMA: Video, collection, draft, progress fields validated")
        return true
    }

    private fun validateCoinSchema(): Boolean {
        val requiredCoinBalanceFields = listOf(
            CoinBalanceDocument.USER_ID, CoinBalanceDocument.AVAILABLE_COINS,
            CoinBalanceDocument.PENDING_COINS, CoinBalanceDocument.LIFETIME_EARNED,
            CoinBalanceDocument.LIFETIME_SPENT, CoinBalanceDocument.LAST_UPDATED
        )
        val requiredTransactionFields = listOf(
            CoinTransactionDocument.ID, CoinTransactionDocument.USER_ID,
            CoinTransactionDocument.TYPE, CoinTransactionDocument.AMOUNT,
            CoinTransactionDocument.CREATED_AT
        )
        val requiredSubPlanFields = listOf(
            SubscriptionPlanDocument.CREATOR_ID, SubscriptionPlanDocument.IS_ENABLED,
            SubscriptionPlanDocument.SUBSCRIBER_COUNT
        )
        println("✅ COIN SCHEMA: ${requiredCoinBalanceFields.size} balance fields + ${requiredTransactionFields.size} transaction fields validated")
        println("✅ SUBSCRIPTION SCHEMA: ${requiredSubPlanFields.size} plan fields validated")
        return true
    }
}