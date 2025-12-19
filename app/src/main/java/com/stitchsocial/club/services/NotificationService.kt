/*
 * NotificationService.kt - COMPLETE WITH WRITE OPERATIONS
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Load, create, and manage notifications
 * Dependencies: Firebase Firestore
 * Features: Real-time loading, notification creation, mark as read, pagination
 *
 * ADDED: createNotification() method for engagement notifications
 */

package com.stitchsocial.club.services

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.tasks.await
import java.util.*

/**
 * Service for loading and managing Firebase notifications
 * USES ROOT COLLECTION to match iOS implementation
 */
class NotificationService {

    private val db = FirebaseFirestore.getInstance("stitchfin")
    private var listenerRegistration: ListenerRegistration? = null
    private val notificationsCollection = "notifications"  // ROOT collection like iOS

    // MARK: - CREATE NOTIFICATION (NEW)

    /**
     * Create a new notification in Firebase
     * Used by EngagementCoordinator to notify video creators
     */
    suspend fun createNotification(
        type: StitchNotificationType,
        title: String,
        message: String,
        senderID: String,
        recipientID: String,
        payload: Map<String, String> = emptyMap()
    ): Boolean {
        return try {
            println("📤 NOTIFICATION SERVICE: Creating ${type.displayName} notification")
            println("   Sender: $senderID → Recipient: $recipientID")

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

            println("✅ NOTIFICATION SERVICE: Notification created successfully")
            true

        } catch (e: Exception) {
            println("❌ NOTIFICATION SERVICE: Failed to create notification - ${e.message}")
            false
        }
    }

    // MARK: - Load Notifications

    /**
     * Load notifications for a user with pagination
     * FIXED: Queries ROOT collection with recipientID filter
     */
    suspend fun loadNotifications(
        userID: String,
        limit: Int = 20,
        lastDocument: DocumentSnapshot? = null
    ): NotificationLoadResult {
        return try {
            println("📢 NOTIFICATION SERVICE: Loading notifications for user $userID")

            var query = db.collection(notificationsCollection)
                .whereEqualTo("recipientID", userID)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())

            // Add pagination if last document provided
            if (lastDocument != null) {
                query = query.startAfter(lastDocument)
            }

            val snapshot = query.get().await()

            val notifications = snapshot.documents.mapNotNull { doc ->
                parseNotification(doc)
            }

            val hasMore = snapshot.documents.size >= limit
            val lastDoc = snapshot.documents.lastOrNull()

            println("✅ NOTIFICATION SERVICE: Loaded ${notifications.size} notifications")

            NotificationLoadResult(
                notifications = notifications,
                hasMore = hasMore,
                lastDocument = lastDoc
            )

        } catch (e: Exception) {
            println("❌ NOTIFICATION SERVICE: Failed to load notifications - ${e.message}")
            NotificationLoadResult(
                notifications = emptyList(),
                hasMore = false,
                lastDocument = null
            )
        }
    }

    /**
     * Start real-time listener for notifications
     * FIXED: Listens to ROOT collection with recipientID filter
     */
    fun startListening(
        userID: String,
        onNotificationsUpdated: (List<StitchNotification>) -> Unit
    ) {
        stopListening()

        println("📡 NOTIFICATION SERVICE: Starting real-time listener for user $userID")

        listenerRegistration = db.collection(notificationsCollection)
            .whereEqualTo("recipientID", userID)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    println("❌ NOTIFICATION SERVICE: Listener error - ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshot == null) {
                    println("⚠️ NOTIFICATION SERVICE: Null snapshot")
                    return@addSnapshotListener
                }

                val notifications = snapshot.documents.mapNotNull { doc ->
                    parseNotification(doc)
                }

                println("🔄 NOTIFICATION SERVICE: Listener update - ${notifications.size} notifications")
                onNotificationsUpdated(notifications)
            }
    }

    /**
     * Stop real-time listener
     */
    fun stopListening() {
        listenerRegistration?.remove()
        listenerRegistration = null
        println("🔴 NOTIFICATION SERVICE: Stopped real-time listener")
    }

    // MARK: - Mark as Read

    /**
     * Mark single notification as read
     * FIXED: Updates ROOT collection document
     */
    suspend fun markAsRead(userID: String, notificationID: String): Boolean {
        return try {
            println("✓ NOTIFICATION SERVICE: Marking notification $notificationID as read")

            db.collection(notificationsCollection)
                .document(notificationID)
                .update(mapOf(
                    "isRead" to true,
                    "readAt" to FieldValue.serverTimestamp()
                ))
                .await()

            println("✅ NOTIFICATION SERVICE: Marked as read")
            true

        } catch (e: Exception) {
            println("❌ NOTIFICATION SERVICE: Failed to mark as read - ${e.message}")
            false
        }
    }

    /**
     * Mark all notifications as read for a user
     * FIXED: Queries and updates ROOT collection
     */
    suspend fun markAllAsRead(userID: String): Boolean {
        return try {
            println("✓ NOTIFICATION SERVICE: Marking all notifications as read for user $userID")

            val unreadDocs = db.collection(notificationsCollection)
                .whereEqualTo("recipientID", userID)
                .whereEqualTo("isRead", false)
                .get()
                .await()

            println("📊 NOTIFICATION SERVICE: Found ${unreadDocs.documents.size} unread notifications")

            // Batch update
            val batch = db.batch()
            unreadDocs.documents.forEach { doc ->
                batch.update(
                    doc.reference,
                    mapOf(
                        "isRead" to true,
                        "readAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.commit().await()

            println("✅ NOTIFICATION SERVICE: Marked all as read")
            true

        } catch (e: Exception) {
            println("❌ NOTIFICATION SERVICE: Failed to mark all as read - ${e.message}")
            false
        }
    }

    // MARK: - Get Unread Count

    /**
     * Get unread notification count
     */
    suspend fun getUnreadCount(userID: String): Int {
        return try {
            val snapshot = db.collection(notificationsCollection)
                .whereEqualTo("recipientID", userID)
                .whereEqualTo("isRead", false)
                .count()
                .get(com.google.firebase.firestore.AggregateSource.SERVER)
                .await()

            val count = snapshot.count.toInt()
            println("📊 NOTIFICATION SERVICE: Unread count: $count")
            count

        } catch (e: Exception) {
            println("❌ NOTIFICATION SERVICE: Failed to get unread count - ${e.message}")
            0
        }
    }

    // MARK: - Parse Notification

    /**
     * Parse Firestore document into StitchNotification
     */
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
                payload = (data["payload"] as? Map<*, *>)?.mapKeys { it.key.toString() }?.mapValues { it.value.toString() } ?: emptyMap(),
                isRead = (data["isRead"] as? Boolean) ?: false,
                createdAt = (data["createdAt"] as? Timestamp)?.toDate() ?: Date(),
                readAt = (data["readAt"] as? Timestamp)?.toDate()
            )

        } catch (e: Exception) {
            println("❌ NOTIFICATION SERVICE: Failed to parse notification ${doc.id} - ${e.message}")
            null
        }
    }

    /**
     * Parse notification type string to enum
     */
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
            "system" -> StitchNotificationType.SYSTEM
            else -> StitchNotificationType.SYSTEM
        }
    }
}

// MARK: - Data Classes

/**
 * Result from loading notifications
 */
data class NotificationLoadResult(
    val notifications: List<StitchNotification>,
    val hasMore: Boolean,
    val lastDocument: DocumentSnapshot?
)

/**
 * Notification data model matching Firebase structure
 */
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

/**
 * Notification types matching Firebase
 */
enum class StitchNotificationType(val rawValue: String) {
    HYPE("hype"),
    COOL("cool"),
    REPLY("reply"),
    FOLLOW("follow"),
    MENTION("mention"),
    SHARE("share"),
    MILESTONE("milestone"),
    TIER_UPGRADE("tier_upgrade"),
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
            SYSTEM -> "info"
        }
}