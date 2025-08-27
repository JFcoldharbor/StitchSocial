/*
 * ServiceProtocols.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 2: Protocols & Events - ZERO External Dependencies
 * Can import: Layer 1 (Foundation) ONLY
 * NO kotlinx.coroutines, NO android.*, NO external libraries
 */

package com.example.stitchsocialclub.protocols

// MARK: - Video Service Protocol

interface VideoService {
    suspend fun createThread(
        title: String,
        videoURL: String,
        thumbnailURL: String,
        creatorID: String,
        creatorName: String,
        duration: Double,
        fileSize: Long
    ): Any
    suspend fun getVideo(id: String): Any?
    suspend fun getThread(threadID: String): Any?
    suspend fun getFollowingFeed(userID: String, limit: Int, lastDocument: Any?): Any
    suspend fun updateEngagement(videoID: String, interactionType: Any, userID: String): Boolean
    suspend fun uploadVideo(videoUri: Any, metadata: Any): Any
    suspend fun processVideoForUpload(videoUri: Any): Any
    suspend fun getHomeFeed(userId: String, limit: Int): Any
    suspend fun getDiscoveryFeed(userId: String, limit: Int): Any
    suspend fun recordEngagement(userId: String, videoId: String, type: Any, tapCount: Int): Any
    suspend fun createVideoReply(parentVideoId: String, replyMetadata: Any): Any
}

// MARK: - User Service Protocol

interface UserService {
    suspend fun createUserProfile(userData: Any): Any
    suspend fun updateUserProfile(userId: String, updates: Map<String, Any>): Any
    suspend fun getUserProfile(userId: String): Any?
    suspend fun followUser(followerId: String, followeeId: String): Any
    suspend fun unfollowUser(followerId: String, followeeId: String): Any
    suspend fun getFollowingFeed(userId: String): Any
    suspend fun searchUsers(query: String): Any
    suspend fun updateUserTier(userId: String, progression: Any): Any
    suspend fun getUserTier(userId: String): Any
    suspend fun validateSpecialUser(email: String): Any
}

// MARK: - Authentication Service Protocol

interface AuthenticationService {
    suspend fun signIn(email: String, password: String): Any
    suspend fun signUp(email: String, password: String, displayName: String, username: String): Any
    suspend fun signOut(): Any
    suspend fun getCurrentUser(): Any?
    suspend fun refreshSession(): Any
    suspend fun resetPassword(email: String): Any
    suspend fun isSpecialUser(email: String): Boolean
}

// MARK: - Notification Service Protocol

interface NotificationService {
    suspend fun sendNotification(notification: Any): Any
    suspend fun scheduleNotification(notification: Any, delay: Long): Any
    suspend fun cancelNotification(notificationId: String): Any
    suspend fun registerForPushNotifications(): Any
    suspend fun updateNotificationSettings(userId: String, settings: Any): Any
    suspend fun sendEngagementReward(userId: String, rewardType: Any, details: Any): Any
    suspend fun sendProgressiveTapUpdate(userId: String, milestone: Any, details: Any): Any
}

// MARK: - Analytics Service Protocol

interface AnalyticsService {
    suspend fun trackEvent(event: Any): Any
    suspend fun trackUserAction(userId: String, action: Any, context: Map<String, Any>): Any
    suspend fun trackVideoEngagement(userId: String, videoId: String, engagementType: Any, duration: Long): Any
    suspend fun trackScreenView(screenName: String, userId: String): Any
    suspend fun setUserProperties(userId: String, properties: Map<String, Any>): Any
    suspend fun flush(): Any
}

// MARK: - Upload Service Protocol

interface UploadService {
    suspend fun uploadVideo(videoUri: Any, metadata: Any): Any
    suspend fun uploadThumbnail(imageUri: Any, videoId: String): Any
    suspend fun cancelUpload(uploadId: String): Any
    suspend fun retryUpload(uploadId: String): Any
    suspend fun getUploadProgress(uploadId: String): Any
}

// MARK: - Content Analysis Service Protocol

interface ContentAnalysisService {
    suspend fun analyzeVideo(videoUri: Any, userID: String): Any?
    suspend fun generateTitle(transcript: String, userTier: Any): String
    suspend fun generateDescription(transcript: String, title: String, userTier: Any): String
    suspend fun generateHashtags(content: String, userTier: Any): List<String>
    suspend fun validateContent(title: String, description: String, hashtags: List<String>): Any
    suspend fun calculateAIConfidence(transcript: String, contentQuality: Double, processingTime: Double): Double
}

// MARK: - Video Processing Service Protocol

interface VideoProcessingService {
    suspend fun compressVideo(inputUri: Any, outputUri: Any, quality: Any): Any
    suspend fun generateThumbnail(videoUri: Any): Any
    suspend fun extractVideoMetadata(videoUri: Any): Any
    suspend fun validateVideoFormat(videoUri: Any): Boolean
    suspend fun optimizeForUpload(videoUri: Any): Any
}