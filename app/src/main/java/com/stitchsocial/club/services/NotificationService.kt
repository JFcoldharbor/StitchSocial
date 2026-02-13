/*
 * NotificationService.kt - CLOUD FUNCTIONS PORT (MATCHES iOS)
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Notification Management with Cloud Functions
 * Codebase: stitchnoti
 * Region: us-central1
 * Database: stitchfin
 *
 * ✅ REWRITTEN: Now calls Cloud Functions like iOS instead of direct Firestore writes
 * ✅ Cloud Functions handle: auth, username lookup, notification creation, FCM push
 * ✅ Keeps: Firestore reads (loadNotifications, listener, markAsRead) - same as iOS
 */

package com.stitchsocial.club.services

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.FieldValue
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import java.util.*

/**
 * Notification service matching iOS implementation exactly.
 * SEND methods → Cloud Functions (stitchnoti_*)
 * READ methods → Direct Firestore queries
 */
class NotificationService {

    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val auth = FirebaseAuth.getInstance()
    private val functions = FirebaseFunctions.getInstance("us-central1")
    private var listenerRegistration: ListenerRegistration? = null

    private val notificationsCollection = "notifications"
    private val functionPrefix = "stitchnoti_"

    init {
        println("NOTIFICATION SERVICE: Initialized (Cloud Functions mode)")
        println("  REGION: us-central1 | PREFIX: $functionPrefix")
    }

    // ========================================================================
    // MARK: - Cloud Function Caller (matches iOS callFunction)
    // ========================================================================

    /**
     * Call Cloud Function via Firebase Callable SDK (handles auth automatically)
     * Matches iOS: private func callFunction(name:data:)
     */
    private suspend fun callFunction(
        name: String,
        data: Map<String, Any> = emptyMap()
    ): Map<String, Any>? {
        val user = auth.currentUser
            ?: throw IllegalStateException("User not authenticated")

        val functionName = "$functionPrefix$name"
        println("CALLING: $functionName (uid: ${user.uid})")

        return try {
            val callable = functions.getHttpsCallable(functionName)
            val result = callable.call(data).await()

            println("SUCCESS: $functionName")

            @Suppress("UNCHECKED_CAST")
            result.data as? Map<String, Any>

        } catch (e: Exception) {
            println("ERROR: $functionName failed - ${e.message}")
            throw e
        }
    }

    // ========================================================================
    // MARK: - Engagement Notifications (via Cloud Functions)
    // ========================================================================

    /**
     * Send engagement notification (hype/cool)
     * Matches iOS: sendEngagementNotification(to:videoID:engagementType:videoTitle:)
     * Cloud Function resolves sender username from auth context
     */
    suspend fun sendEngagementNotification(
        recipientID: String,
        videoID: String,
        engagementType: String,
        videoTitle: String
    ) {
        println("ENGAGEMENT: Sending $engagementType notification")

        val data = mapOf(
            "recipientID" to recipientID,
            "videoID" to videoID,
            "engagementType" to engagementType,
            "videoTitle" to videoTitle
        )

        try {
            val result = callFunction("sendEngagement", data)
            val success = result?.get("success") as? Boolean ?: false
            if (success) println("ENGAGEMENT: Notification sent")
        } catch (e: Exception) {
            println("ENGAGEMENT: Failed - ${e.message}")
        }
    }

    // ========================================================================
    // MARK: - Reply & Follow Notifications (via Cloud Functions)
    // ========================================================================

    /**
     * Send reply notification
     * Matches iOS: sendReplyNotification(to:videoID:videoTitle:)
     */
    suspend fun sendReplyNotification(
        recipientID: String,
        videoID: String,
        videoTitle: String
    ) {
        println("REPLY: Sending reply notification")

        val data = mapOf(
            "recipientID" to recipientID,
            "videoID" to videoID,
            "videoTitle" to videoTitle
        )

        try {
            val result = callFunction("sendReply", data)
            val success = result?.get("success") as? Boolean ?: false
            if (success) println("REPLY: Notification sent")
        } catch (e: Exception) {
            println("REPLY: Failed - ${e.message}")
        }
    }

    /**
     * Send follow notification
     * Matches iOS: sendFollowNotification(to:)
     */
    suspend fun sendFollowNotification(recipientID: String) {
        println("FOLLOW: Sending follow notification")

        val data = mapOf("recipientID" to recipientID)

        try {
            val result = callFunction("sendFollow", data)
            val success = result?.get("success") as? Boolean ?: false
            if (success) println("FOLLOW: Notification sent")
        } catch (e: Exception) {
            println("FOLLOW: Failed - ${e.message}")
        }
    }

    /**
     * Send mention notification
     * Matches iOS: sendMentionNotification(to:videoID:videoTitle:mentionContext:)
     */
    suspend fun sendMentionNotification(
        recipientID: String,
        videoID: String,
        videoTitle: String,
        mentionContext: String = "video"
    ) {
        println("MENTION: Sending mention notification")

        val data = mapOf(
            "recipientID" to recipientID,
            "videoID" to videoID,
            "videoTitle" to videoTitle,
            "mentionContext" to mentionContext
        )

        try {
            val result = callFunction("sendMention", data)
            val success = result?.get("success") as? Boolean ?: false
            if (success) println("MENTION: Notification sent")
        } catch (e: Exception) {
            println("MENTION: Failed - ${e.message}")
        }
    }

    // ========================================================================
    // MARK: - Stitch/Thread Notifications (via Cloud Functions)
    // ========================================================================

    /**
     * Send stitch/reply notification to thread participants
     * Matches iOS: sendStitchNotification(videoID:videoTitle:originalCreatorID:parentCreatorID:threadUserIDs:)
     */
    suspend fun sendStitchNotification(
        videoID: String,
        videoTitle: String,
        originalCreatorID: String,
        parentCreatorID: String?,
        threadUserIDs: List<String>
    ) {
        println("STITCH: Sending stitch notification to ${threadUserIDs.size} thread users")

        val data = mapOf(
            "videoID" to videoID,
            "videoTitle" to videoTitle,
            "originalCreatorID" to originalCreatorID,
            "parentCreatorID" to (parentCreatorID ?: ""),
            "threadUserIDs" to threadUserIDs
        )

        try {
            val result = callFunction("sendStitch", data)
            val success = result?.get("success") as? Boolean ?: false
            if (success) println("STITCH: Notifications sent")
        } catch (e: Exception) {
            println("STITCH: Failed - ${e.message}")
        }
    }

    // ========================================================================
    // MARK: - Milestone Notifications (via Cloud Functions)
    // ========================================================================

    /**
     * Send milestone notification to creator, followers, and engagers
     * Matches iOS: sendMilestoneNotification(milestone:videoID:videoTitle:creatorID:followerIDs:engagerIDs:)
     */
    suspend fun sendMilestoneNotification(
        milestone: Int,
        videoID: String,
        videoTitle: String,
        creatorID: String,
        followerIDs: List<String>,
        engagerIDs: List<String>
    ) {
        println("MILESTONE: Sending $milestone-hype milestone notification")

        val data = mapOf(
            "milestone" to milestone,
            "videoID" to videoID,
            "videoTitle" to videoTitle,
            "creatorID" to creatorID,
            "followerIDs" to followerIDs,
            "engagerIDs" to engagerIDs
        )

        try {
            val result = callFunction("sendMilestone", data)
            val success = result?.get("success") as? Boolean ?: false
            if (success) println("MILESTONE: Notifications sent")
        } catch (e: Exception) {
            println("MILESTONE: Failed - ${e.message}")
        }
    }

    // ========================================================================
    // MARK: - New Video Notifications (via Cloud Functions)
    // ========================================================================

    /**
     * Notify all followers when creator uploads new video
     * Matches iOS: sendNewVideoNotification(creatorID:creatorUsername:videoID:videoTitle:followerIDs:)
     */
    suspend fun sendNewVideoNotification(
        creatorID: String,
        creatorUsername: String,
        videoID: String,
        videoTitle: String,
        followerIDs: List<String>
    ) {
        println("NEW VIDEO: Notifying ${followerIDs.size} followers")

        val data = mapOf(
            "creatorID" to creatorID,
            "creatorUsername" to creatorUsername,
            "videoID" to videoID,
            "videoTitle" to videoTitle,
            "followerIDs" to followerIDs
        )

        try {
            val result = callFunction("sendNewVideo", data)
            val success = result?.get("success") as? Boolean ?: false
            if (success) println("NEW VIDEO: Notifications sent")
        } catch (e: Exception) {
            println("NEW VIDEO: Failed - ${e.message}")
        }
    }

    // ========================================================================
    // MARK: - Test & Debug (via Cloud Functions)
    // ========================================================================

    /**
     * Send test push notification
     * Matches iOS: sendTestPush()
     */
    suspend fun sendTestPush(): Boolean {
        return try {
            val result = callFunction("sendTestPush")
            val success = result?.get("success") as? Boolean ?: false
            if (success) println("TEST PUSH: Sent successfully")
            success
        } catch (e: Exception) {
            println("TEST PUSH: Failed - ${e.message}")
            false
        }
    }

    /**
     * Check FCM token status
     * Matches iOS: checkToken()
     */
    suspend fun checkToken(): Map<String, Any>? {
        return try {
            val result = callFunction("checkToken")
            println("TOKEN CHECK: $result")
            result
        } catch (e: Exception) {
            println("TOKEN CHECK: Failed - ${e.message}")
            null
        }
    }

    // ========================================================================
    // MARK: - Legacy Direct Write (fallback only)
    // ========================================================================

    /**
     * Create notification directly in Firestore (LEGACY)
     * Only use if Cloud Functions are unavailable
     */
    suspend fun createNotificationDirect(
        type: StitchNotificationType,
        title: String,
        message: String,
        senderID: String,
        recipientID: String,
        payload: Map<String, String> = emptyMap()
    ): Boolean {
        return try {
            val notificationData = hashMapOf(
                "type" to type.rawValue,
                "title" to title,
                "message" to message,
                "senderID" to senderID,
                "recipientID" to recipientID,
                "payload" to payload,
                "isRead" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "readAt" to null
            )

            db.collection(notificationsCollection)
                .add(notificationData)
                .await()

            true

        } catch (e: Exception) {
            println("NOTIFICATION DIRECT WRITE: Failed - ${e.message}")
            false
        }
    }

    // ========================================================================
    // MARK: - Load Notifications (Direct Firestore - same as iOS)
    // ========================================================================

    suspend fun loadNotifications(
        userID: String,
        limit: Int = 20,
        lastDocument: DocumentSnapshot? = null
    ): NotificationLoadResult {
        return try {
            var query = db.collection(notificationsCollection)
                .whereEqualTo("recipientID", userID)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())

            if (lastDocument != null) {
                query = query.startAfter(lastDocument)
            }

            val snapshot = query.get().await()

            val notifications = snapshot.documents.mapNotNull { doc ->
                parseNotification(doc)
            }

            NotificationLoadResult(
                notifications = notifications,
                hasMore = snapshot.documents.size >= limit,
                lastDocument = snapshot.documents.lastOrNull()
            )

        } catch (e: Exception) {
            println("LOAD NOTIFICATIONS: Failed - ${e.message}")
            NotificationLoadResult(emptyList(), false, null)
        }
    }

    // ========================================================================
    // MARK: - Real-time Listener (Direct Firestore - same as iOS)
    // ========================================================================

    fun startListening(
        userID: String,
        onNotificationsUpdated: (List<StitchNotification>) -> Unit
    ) {
        stopListening()

        listenerRegistration = db.collection(notificationsCollection)
            .whereEqualTo("recipientID", userID)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    println("NOTIFICATION LISTENER: Error - ${error.message}")
                    return@addSnapshotListener
                }

                val notifications = snapshot?.documents?.mapNotNull { doc ->
                    parseNotification(doc)
                } ?: emptyList()

                onNotificationsUpdated(notifications)
            }
    }

    fun stopListening() {
        listenerRegistration?.remove()
        listenerRegistration = null
    }

    // ========================================================================
    // MARK: - Mark as Read (Direct Firestore - same as iOS)
    // ========================================================================

    suspend fun markAsRead(userID: String, notificationID: String): Boolean {
        return try {
            db.collection(notificationsCollection)
                .document(notificationID)
                .update(mapOf(
                    "isRead" to true,
                    "readAt" to FieldValue.serverTimestamp()
                ))
                .await()
            true
        } catch (e: Exception) {
            println("MARK READ: Failed - ${e.message}")
            false
        }
    }

    suspend fun markAllAsRead(userID: String): Boolean {
        return try {
            val unreadDocs = db.collection(notificationsCollection)
                .whereEqualTo("recipientID", userID)
                .whereEqualTo("isRead", false)
                .get()
                .await()

            val batch = db.batch()
            unreadDocs.documents.forEach { doc ->
                batch.update(doc.reference, mapOf(
                    "isRead" to true,
                    "readAt" to FieldValue.serverTimestamp()
                ))
            }
            batch.commit().await()
            true
        } catch (e: Exception) {
            println("MARK ALL READ: Failed - ${e.message}")
            false
        }
    }

    // ========================================================================
    // MARK: - Get Unread Count
    // ========================================================================

    suspend fun getUnreadCount(userID: String): Int {
        return try {
            val snapshot = db.collection(notificationsCollection)
                .whereEqualTo("recipientID", userID)
                .whereEqualTo("isRead", false)
                .count()
                .get(com.google.firebase.firestore.AggregateSource.SERVER)
                .await()
            snapshot.count.toInt()
        } catch (e: Exception) {
            0
        }
    }

    // ========================================================================
    // MARK: - Parse Notification
    // ========================================================================

    private fun parseNotification(doc: DocumentSnapshot): StitchNotification? {
        return try {
            val data = doc.data ?: return null

            StitchNotification(
                id = doc.id,
                type = parseNotificationType(data["type"] as? String),
                title = (data["title"] as? String) ?: "",
                message = (data["message"] as? String) ?: "",
                senderID = (data["senderID"] as? String) ?: "",
                recipientID = (data["recipientID"] as? String) ?: "",
                payload = (data["payload"] as? Map<*, *>)
                    ?.mapKeys { it.key.toString() }
                    ?.mapValues { it.value.toString() }
                    ?: emptyMap(),
                isRead = (data["isRead"] as? Boolean) ?: false,
                createdAt = (data["createdAt"] as? Timestamp)?.toDate() ?: Date(),
                readAt = (data["readAt"] as? Timestamp)?.toDate()
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseNotificationType(typeString: String?): StitchNotificationType {
        return when (typeString?.lowercase()) {
            "hype" -> StitchNotificationType.HYPE
            "cool" -> StitchNotificationType.COOL
            "reply" -> StitchNotificationType.REPLY
            "follow" -> StitchNotificationType.FOLLOW
            "mention" -> StitchNotificationType.MENTION
            "share" -> StitchNotificationType.SHARE
            "milestone" -> StitchNotificationType.MILESTONE
            "tier_upgrade" -> StitchNotificationType.TIER_UPGRADE
            "spinoff" -> StitchNotificationType.SPIN_OFF
            "system" -> StitchNotificationType.SYSTEM
            else -> StitchNotificationType.SYSTEM
        }
    }

    fun debugConfiguration() {
        println("DEBUG: Notification Service Configuration")
        println("  - Database: stitchfin")
        println("  - Region: us-central1")
        println("  - Function Prefix: $functionPrefix")
        println("  - User: ${auth.currentUser?.uid ?: "none"}")
    }
}

// ========================================================================
// MARK: - Data Classes
// ========================================================================

data class NotificationLoadResult(
    val notifications: List<StitchNotification>,
    val hasMore: Boolean,
    val lastDocument: DocumentSnapshot?
)

data class StitchNotification(
    val id: String,
    val type: StitchNotificationType,
    val title: String,
    val message: String,
    val senderID: String,
    val recipientID: String,
    val payload: Map<String, String>,
    val isRead: Boolean,
    val createdAt: Date,
    val readAt: Date? = null
)

enum class StitchNotificationType(val rawValue: String) {
    HYPE("hype"),
    COOL("cool"),
    REPLY("reply"),
    FOLLOW("follow"),
    MENTION("mention"),
    SHARE("share"),
    MILESTONE("milestone"),
    TIER_UPGRADE("tier_upgrade"),
    SPIN_OFF("spinoff"),
    SYSTEM("system");

    val displayName: String
        get() = when (this) {
            HYPE -> "Hype"
            COOL -> "Cool"
            REPLY -> "Reply"
            FOLLOW -> "New Follower"
            MENTION -> "Mention"
            SHARE -> "Share"
            MILESTONE -> "Milestone"
            TIER_UPGRADE -> "Tier Upgrade"
            SPIN_OFF -> "Spin-off"
            SYSTEM -> "System"
        }

    val iconName: String
        get() = when (this) {
            HYPE -> "favorite"
            COOL -> "ac_unit"
            REPLY -> "reply"
            FOLLOW -> "person_add"
            MENTION -> "alternate_email"
            SHARE -> "share"
            MILESTONE -> "emoji_events"
            TIER_UPGRADE -> "arrow_upward"
            SPIN_OFF -> "call_split"
            SYSTEM -> "info"
        }
}