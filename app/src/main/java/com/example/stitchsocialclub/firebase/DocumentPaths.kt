package com.example.stitchsocialclub.firebase

import com.example.stitchsocialclub.firebase.FirebaseSchema.Collections

/**
 * DocumentPaths.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 3: Firebase Foundation - Document Path Constants & Query Builders
 * Dependencies: Layer 2 (Protocols), Layer 1 (Foundation), FirebaseSchema only
 * Database: stitchfin path management and query construction
 *
 * Exact translation from Swift DocumentPaths patterns in UserService.swift and VideoService.swift
 */

// MARK: - Document Path Constants

object DocumentPaths {

    // MARK: - Base Paths

    /** Base project path for stitchfin database */
    private const val BASE_PROJECT_PATH = "projects/${FirestoreConfig.PROJECT_ID}/databases/${FirestoreConfig.DATABASE_NAME}/documents"

    // MARK: - Collection Paths

    /** Get full collection path for stitchfin database */
    fun collectionPath(collection: String): String {
        return "$BASE_PROJECT_PATH/$collection"
    }

    /** Video collection full path */
    val videos: String get() = collectionPath(Collections.VIDEOS)

    /** User collection full path */
    val users: String get() = collectionPath(Collections.USERS)

    /** Thread collection full path */
    val threads: String get() = collectionPath(Collections.THREADS)

    /** Engagement collection full path */
    val engagement: String get() = collectionPath(Collections.ENGAGEMENT)

    /** Interaction collection full path */
    val interactions: String get() = collectionPath(Collections.INTERACTIONS)

    /** Tap progress collection full path */
    val tapProgress: String get() = collectionPath(Collections.TAP_PROGRESS)

    /** Notification collection full path */
    val notifications: String get() = collectionPath(Collections.NOTIFICATIONS)

    /** Following collection full path */
    val following: String get() = collectionPath(Collections.FOLLOWING)

    /** User badges collection full path */
    val userBadges: String get() = collectionPath(Collections.USER_BADGES)

    /** Progression collection full path */
    val progression: String get() = collectionPath(Collections.PROGRESSION)

    /** Analytics collection full path */
    val analytics: String get() = collectionPath(Collections.ANALYTICS)

    /** Comments collection full path */
    val comments: String get() = collectionPath(Collections.COMMENTS)

    /** Reports collection full path */
    val reports: String get() = collectionPath(Collections.REPORTS)

    /** Cache collection full path */
    val cache: String get() = collectionPath(Collections.CACHE)

    // MARK: - Document Paths

    /** Get full document path for specific video */
    fun videoDocument(videoId: String): String {
        return "$videos/$videoId"
    }

    /** Get full document path for specific user */
    fun userDocument(userId: String): String {
        return "$users/$userId"
    }

    /** Get full document path for specific thread */
    fun threadDocument(threadId: String): String {
        return "$threads/$threadId"
    }

    /** Get full document path for specific engagement */
    fun engagementDocument(videoId: String): String {
        return "$engagement/$videoId"
    }

    /** Get full document path for specific interaction */
    fun interactionDocument(interactionId: String): String {
        return "$interactions/$interactionId"
    }

    /** Get full document path for specific tap progress */
    fun tapProgressDocument(progressId: String): String {
        return "$tapProgress/$progressId"
    }

    /** Get full document path for specific notification */
    fun notificationDocument(notificationId: String): String {
        return "$notifications/$notificationId"
    }

    /** Get full document path for specific following relationship */
    fun followingDocument(followerId: String, followingId: String): String {
        return "$following/${followerId}_$followingId"
    }

    /** Get full document path for user badges */
    fun userBadgesDocument(userId: String): String {
        return "$userBadges/$userId"
    }

    /** Get full document path for user progression */
    fun progressionDocument(userId: String): String {
        return "$progression/$userId"
    }

    // MARK: - Subcollection Paths

    /** Get user's following subcollection path (alternative pattern) */
    fun userFollowingSubcollection(userId: String): String {
        return "$users/$userId/following"
    }

    /** Get user's followers subcollection path (alternative pattern) */
    fun userFollowersSubcollection(userId: String): String {
        return "$users/$userId/followers"
    }

    /** Get video's interactions subcollection path */
    fun videoInteractionsSubcollection(videoId: String): String {
        return "$videos/$videoId/interactions"
    }

    /** Get thread's replies subcollection path */
    fun threadRepliesSubcollection(threadId: String): String {
        return "$threads/$threadId/replies"
    }

    /** Get user's notifications subcollection path */
    fun userNotificationsSubcollection(userId: String): String {
        return "$users/$userId/notifications"
    }

    /** Get user's analytics subcollection path */
    fun userAnalyticsSubcollection(userId: String): String {
        return "$users/$userId/analytics"
    }

    // MARK: - Thread Hierarchy Paths

    /** Get path for thread children (child videos) */
    fun threadChildrenPath(threadId: String): String {
        return videos // Children are in main videos collection with threadID field
    }

    /** Get path for child stepchildren (stepchild videos) */
    fun childStepchildrenPath(childId: String): String {
        return videos // Stepchildren are in main videos collection with replyToVideoID field
    }

    // MARK: - Query Builder Utilities

    /** Build query path for videos by creator */
    fun videosByCreatorQuery(creatorId: String): QueryPathBuilder {
        return QueryPathBuilder(videos)
            .whereField("creatorID", "==", creatorId)
            .orderBy("createdAt", descending = true)
    }

    /** Build query path for videos by thread */
    fun videosByThreadQuery(threadId: String): QueryPathBuilder {
        return QueryPathBuilder(videos)
            .whereField("threadID", "==", threadId)
            .orderBy("conversationDepth")
            .orderBy("createdAt")
    }

    /** Build query path for thread children */
    fun threadChildrenQuery(threadId: String): QueryPathBuilder {
        return QueryPathBuilder(videos)
            .whereField("threadID", "==", threadId)
            .whereField("conversationDepth", "==", 1)
            .orderBy("createdAt")
    }

    /** Build query path for child stepchildren */
    fun childStepchildrenQuery(childId: String): QueryPathBuilder {
        return QueryPathBuilder(videos)
            .whereField("replyToVideoID", "==", childId)
            .whereField("conversationDepth", "==", 2)
            .orderBy("createdAt")
    }

    /** Build query path for user's following */
    fun userFollowingQuery(userId: String): QueryPathBuilder {
        return QueryPathBuilder(following)
            .whereField("followerID", "==", userId)
            .whereField("isActive", "==", true)
            .orderBy("createdAt", descending = true)
    }

    /** Build query path for user's followers */
    fun userFollowersQuery(userId: String): QueryPathBuilder {
        return QueryPathBuilder(following)
            .whereField("followingID", "==", userId)
            .whereField("isActive", "==", true)
            .orderBy("createdAt", descending = true)
    }

    /** Build query path for user's notifications */
    fun userNotificationsQuery(userId: String): QueryPathBuilder {
        return QueryPathBuilder(notifications)
            .whereField("recipientID", "==", userId)
            .orderBy("createdAt", descending = true)
    }

    /** Build query path for unread notifications */
    fun unreadNotificationsQuery(userId: String): QueryPathBuilder {
        return QueryPathBuilder(notifications)
            .whereField("recipientID", "==", userId)
            .whereField("isRead", "==", false)
            .orderBy("createdAt", descending = true)
    }

    /** Build query path for user engagement history */
    fun userEngagementQuery(userId: String): QueryPathBuilder {
        return QueryPathBuilder(interactions)
            .whereField("userID", "==", userId)
            .orderBy("timestamp", descending = true)
    }

    /** Build query path for video engagement */
    fun videoEngagementQuery(videoId: String): QueryPathBuilder {
        return QueryPathBuilder(interactions)
            .whereField("videoID", "==", videoId)
            .orderBy("timestamp", descending = true)
    }

    /** Build query path for trending videos */
    fun trendingVideosQuery(): QueryPathBuilder {
        return QueryPathBuilder(videos)
            .whereField("temperature", "==", "hot")
            .whereField("conversationDepth", "==", 0)
            .orderBy("lastEngagementAt", descending = true)
    }

    /** Build query path for discovery feed */
    fun discoveryFeedQuery(): QueryPathBuilder {
        return QueryPathBuilder(videos)
            .whereField("conversationDepth", "==", 0)
            .whereField("isPromoted", "==", false)
            .orderBy("discoverabilityScore", descending = true)
            .orderBy("createdAt", descending = true)
    }

    // MARK: - Batch Operation Paths

    /** Get paths for batch video operations */
    fun batchVideoPaths(videoIds: List<String>): List<String> {
        return videoIds.map { videoDocument(it) }
    }

    /** Get paths for batch user operations */
    fun batchUserPaths(userIds: List<String>): List<String> {
        return userIds.map { userDocument(it) }
    }

    /** Get paths for batch engagement operations */
    fun batchEngagementPaths(videoIds: List<String>): List<String> {
        return videoIds.map { engagementDocument(it) }
    }

    // MARK: - Cache Key Generation

    /** Generate cache key for collection */
    fun collectionCacheKey(collection: String, query: String? = null): String {
        return if (query != null) {
            "stitchfin_${collection}_$query"
        } else {
            "stitchfin_$collection"
        }
    }

    /** Generate cache key for document */
    fun documentCacheKey(collection: String, documentId: String): String {
        return "stitchfin_${collection}_$documentId"
    }

    /** Generate cache key for user-specific data */
    fun userCacheKey(userId: String, dataType: String): String {
        return "stitchfin_user_${userId}_$dataType"
    }

    // MARK: - Path Validation

    /** Validate collection path format */
    fun isValidCollectionPath(path: String): Boolean {
        return path.startsWith(BASE_PROJECT_PATH) && 
               path.split("/").size == BASE_PROJECT_PATH.split("/").size + 1
    }

    /** Validate document path format */
    fun isValidDocumentPath(path: String): Boolean {
        return path.startsWith(BASE_PROJECT_PATH) && 
               path.split("/").size == BASE_PROJECT_PATH.split("/").size + 2
    }

    /** Validate subcollection path format */
    fun isValidSubcollectionPath(path: String): Boolean {
        return path.startsWith(BASE_PROJECT_PATH) && 
               path.split("/").size >= BASE_PROJECT_PATH.split("/").size + 3
    }

    // MARK: - Path Utilities

    /** Extract collection name from full path */
    fun extractCollectionName(path: String): String? {
        val parts = path.split("/")
        val basePathParts = BASE_PROJECT_PATH.split("/")
        return if (parts.size > basePathParts.size) {
            parts[basePathParts.size]
        } else null
    }

    /** Extract document ID from full path */
    fun extractDocumentId(path: String): String? {
        val parts = path.split("/")
        val basePathParts = BASE_PROJECT_PATH.split("/")
        return if (parts.size > basePathParts.size + 1) {
            parts[basePathParts.size + 1]
        } else null
    }

    /** Get all collection names */
    fun getAllCollectionNames(): List<String> {
        return listOf(
            Collections.VIDEOS, Collections.USERS, Collections.THREADS,
            Collections.ENGAGEMENT, Collections.INTERACTIONS, Collections.TAP_PROGRESS,
            Collections.NOTIFICATIONS, Collections.FOLLOWING, Collections.USER_BADGES,
            Collections.PROGRESSION, Collections.ANALYTICS, Collections.COMMENTS,
            Collections.REPORTS, Collections.CACHE
        )
    }

    /** Print path diagnostics for debugging */
    fun printPathDiagnostics() {
        println("🔍 DOCUMENT PATHS: Path diagnostics for stitchfin database")
        println("   Base Path: $BASE_PROJECT_PATH")
        println("   Collections: ${getAllCollectionNames().size}")
        println("   Example Video Path: ${videoDocument("test123")}")
        println("   Example User Path: ${userDocument("user456")}")
        println("   Example Query: ${videosByCreatorQuery("user456").build()}")
    }
}

// MARK: - Query Path Builder

/**
 * Helper class for building Firestore query paths
 * Supports method chaining for complex queries
 */
class QueryPathBuilder(private val basePath: String) {
    private val whereConditions = mutableListOf<String>()
    private val orderByConditions = mutableListOf<String>()
    private var limitValue: Int? = null
    private var offsetValue: Int? = null

    fun whereField(field: String, operator: String, value: Any): QueryPathBuilder {
        whereConditions.add("$field $operator $value")
        return this
    }

    fun orderBy(field: String, descending: Boolean = false): QueryPathBuilder {
        val direction = if (descending) "DESC" else "ASC"
        orderByConditions.add("$field $direction")
        return this
    }

    fun limit(count: Int): QueryPathBuilder {
        limitValue = count
        return this
    }

    fun offset(count: Int): QueryPathBuilder {
        offsetValue = count
        return this
    }

    fun build(): String {
        val parts = mutableListOf<String>()
        parts.add("path: $basePath")
        
        if (whereConditions.isNotEmpty()) {
            parts.add("where: ${whereConditions.joinToString(" AND ")}")
        }
        
        if (orderByConditions.isNotEmpty()) {
            parts.add("orderBy: ${orderByConditions.joinToString(", ")}")
        }
        
        limitValue?.let { parts.add("limit: $it") }
        offsetValue?.let { parts.add("offset: $it") }
        
        return parts.joinToString(", ")
    }

    /** Get the base collection path */
    fun getBasePath(): String = basePath

    /** Get where conditions */
    fun getWhereConditions(): List<String> = whereConditions.toList()

    /** Get order by conditions */
    fun getOrderByConditions(): List<String> = orderByConditions.toList()

    /** Get limit value */
    fun getLimit(): Int? = limitValue

    /** Get offset value */
    fun getOffset(): Int? = offsetValue
}