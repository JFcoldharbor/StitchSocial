package com.example.stitchsocialclub.protocols

// MARK: - Layer 2: Event Protocols - ZERO External Dependencies
// Can import: Layer 1 (Foundation) ONLY
// NO kotlinx.coroutines, NO android.*, NO external libraries

// MARK: - Event Bus Protocol

interface EventBus {
    suspend fun publish(event: Any): Any
    fun subscribeToEventType(eventType: Any): Any
    fun subscribeWithFilter(filter: Any): Any
    suspend fun unsubscribe(subscription: Any): Any
    suspend fun clear(): Any
}

// MARK: - Event Listener Protocol

interface EventListener {
    suspend fun onEvent(event: Any)
    fun canHandle(event: Any): Boolean
}

// MARK: - Event Dispatcher Protocol

interface EventDispatcher {
    suspend fun dispatch(event: Any): Any
    suspend fun dispatchToUser(userId: String, event: Any): Any
    suspend fun dispatchBroadcast(event: Any): Any
    suspend fun scheduleEvent(event: Any, delay: Long): Any
    suspend fun cancelScheduledEvent(eventId: String): Any
}

// MARK: - Progressive Tap Event Protocol

interface ProgressiveTapEventHandler {
    suspend fun onTapStarted(event: Any): Any
    suspend fun onTapProgress(event: Any): Any
    suspend fun onTapMilestone(event: Any): Any
    suspend fun onTapCompleted(event: Any): Any
    suspend fun onTapReset(event: Any): Any
}

// MARK: - Engagement Event Protocol

interface EngagementEventHandler {
    suspend fun onEngagementCreated(event: Any): Any
    suspend fun onEngagementUpdated(event: Any): Any
    suspend fun onEngagementStreakAchieved(event: Any): Any
    suspend fun onViralThresholdReached(event: Any): Any
}

// MARK: - User Event Protocol

interface UserEventHandler {
    suspend fun onUserCreated(event: Any): Any
    suspend fun onUserUpdated(event: Any): Any
    suspend fun onUserTierAdvanced(event: Any): Any
    suspend fun onFollowshipChanged(event: Any): Any
}

// MARK: - Video Event Protocol

interface VideoEventHandler {
    suspend fun onVideoUploaded(event: Any): Any
    suspend fun onVideoProcessed(event: Any): Any
    suspend fun onVideoDeleted(event: Any): Any
    suspend fun onVideoReplyCreated(event: Any): Any
}

// MARK: - Notification Event Protocol

interface NotificationEventHandler {
    suspend fun onNotificationCreated(event: Any): Any
    suspend fun onNotificationDelivered(event: Any): Any
    suspend fun onNotificationRead(event: Any): Any
    suspend fun onNotificationExpired(event: Any): Any
}