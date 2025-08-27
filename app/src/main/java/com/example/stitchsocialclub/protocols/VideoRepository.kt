package com.example.stitchsocialclub.protocols

// MARK: - Layer 2: Protocols & Events - ZERO External Dependencies
// Can import: Layer 1 (Foundation) ONLY
// NO kotlinx.coroutines, NO android.*, NO external libraries

// MARK: - Video Repository Protocol

interface VideoRepository {
    suspend fun createVideo(metadata: Any): Any
    suspend fun getVideo(id: String): Any?
    suspend fun updateEngagement(videoId: String, update: Any): Any
    suspend fun deleteVideo(id: String): Any
    suspend fun getThreadData(threadId: String): Any?
    suspend fun getUserVideos(userId: String, limit: Int): Any
    suspend fun getFollowingFeed(followingIds: List<String>, limit: Int): Any
    suspend fun batchUpdateEngagement(updates: List<Any>): Any
    suspend fun getVideoEngagement(videoId: String): Any
}

// MARK: - User Repository Protocol

interface UserRepository {
    suspend fun createUser(userData: Any): Any
    suspend fun getUser(id: String): Any?
    suspend fun updateUser(id: String, updates: Map<String, Any>): Any
    suspend fun deleteUser(id: String): Any
    suspend fun getFollowing(userId: String, limit: Int): Any
    suspend fun getFollowers(userId: String, limit: Int): Any
    suspend fun getFollowingIds(userId: String): Any
    suspend fun followUser(followerId: String, followeeId: String): Any
    suspend fun unfollowUser(followerId: String, followeeId: String): Any
    suspend fun searchUsers(query: String, limit: Int): Any
    suspend fun updateUserTier(userId: String, newTier: Any): Any
}

// MARK: - Notification Repository Protocol

interface NotificationRepository {
    suspend fun createNotification(notification: Any): Any
    suspend fun getNotifications(userId: String, limit: Int): Any
    suspend fun markAsRead(notificationId: String): Any
    suspend fun markAllAsRead(userId: String): Any
    suspend fun deleteNotification(id: String): Any
    suspend fun getUnreadCount(userId: String): Any
    suspend fun batchCreateNotifications(notifications: List<Any>): Any
}

// MARK: - Authentication Repository Protocol

interface AuthRepository {
    suspend fun signIn(email: String, password: String): Any
    suspend fun signUp(email: String, password: String, userData: Any): Any
    suspend fun signOut(): Any
    suspend fun getCurrentUser(): Any?
    suspend fun refreshToken(): Any
    suspend fun resetPassword(email: String): Any
    suspend fun updatePassword(currentPassword: String, newPassword: String): Any
    suspend fun deleteAccount(userId: String): Any
}

// MARK: - Engagement Repository Protocol

interface EngagementRepository {
    suspend fun recordEngagement(engagement: Any): Any
    suspend fun getEngagementHistory(userId: String, limit: Int): Any
    suspend fun getUserEngagementStats(userId: String): Any
    suspend fun getVideoEngagements(videoId: String): Any
    suspend fun updateProgressiveTaps(videoId: String, userId: String, tapCount: Int): Any
    suspend fun batchRecordEngagements(engagements: List<Any>): Any
}