/*
 * FCMService.kt - FIREBASE CLOUD MESSAGING SERVICE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Handle FCM push notifications
 * Dependencies: Firebase Messaging, Notification Service
 * Features: Token registration, background notifications, notification handling
 *
 * EXACT PORT: FCMPushManager.swift functionality for Android
 * ✅ FIXED: Added missing Android API imports
 * ✅ FIXED: Proper PendingIntent flags and extras
 */

package com.stitchsocial.club.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Firebase Cloud Messaging service for handling push notifications
 */
class FCMService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = FirebaseFirestore.getInstance("stitchfin")
    private val auth = FirebaseAuth.getInstance()

    companion object {
        private const val CHANNEL_ID = "stitch_notifications"
        private const val CHANNEL_NAME = "Stitch Social Notifications"
        private const val NOTIFICATION_ID_BASE = 1000

        /**
         * Request FCM token and store in Firebase
         */
        fun registerFCMToken(context: Context) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val token = FirebaseMessaging.getInstance().token.await()
                    println("📱 FCM: Token retrieved: ${token.take(20)}...")

                    val userID = FirebaseAuth.getInstance().currentUser?.uid
                    if (userID != null) {
                        storeFCMToken(context, userID, token)
                    } else {
                        println("⚠️ FCM: No authenticated user for token storage")
                    }

                } catch (e: Exception) {
                    println("❌ FCM: Failed to get token - ${e.message}")
                }
            }
        }

        /**
         * Store FCM token in Firestore
         */
        private suspend fun storeFCMToken(context: Context, userID: String, token: String) {
            try {
                val db = FirebaseFirestore.getInstance("stitchfin")

                val tokenData = hashMapOf(
                    "fcmToken" to token,
                    "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    "platform" to "android",
                    "appVersion" to getAppVersion(context),
                    "isActive" to true
                )

                db.collection("userTokens")
                    .document(userID)
                    .set(tokenData)
                    .await()

                println("✅ FCM: Token stored for user: $userID")

            } catch (e: Exception) {
                println("❌ FCM: Failed to store token - ${e.message}")
            }
        }

        /**
         * Get app version
         */
        private fun getAppVersion(context: Context): String {
            return try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                packageInfo.versionName ?: "1.0"
            } catch (e: Exception) {
                "1.0"
            }
        }
    }

    // MARK: - Lifecycle

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        println("📱 FCM SERVICE: Service created")
    }

    // MARK: - Token Management

    /**
     * Called when FCM token is refreshed
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        println("📱 FCM: New token received: ${token.take(20)}...")

        serviceScope.launch {
            val userID = auth.currentUser?.uid
            if (userID != null) {
                storeFCMToken(applicationContext, userID, token)
            } else {
                println("⚠️ FCM: No user authenticated for new token")
            }
        }
    }

    // MARK: - Message Handling

    /**
     * Called when message is received
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        println("📱 FCM: Message received")
        println("📱 FCM: From: ${remoteMessage.from}")
        println("📱 FCM: Data: ${remoteMessage.data}")

        // Handle notification payload
        remoteMessage.notification?.let { notification ->
            println("📱 FCM: Notification - ${notification.title}: ${notification.body}")
            showNotification(
                title = notification.title ?: "Stitch Social",
                body = notification.body ?: "",
                data = remoteMessage.data
            )
        }

        // Handle data payload
        if (remoteMessage.data.isNotEmpty()) {
            println("📱 FCM: Data payload - ${remoteMessage.data}")
            handleDataPayload(remoteMessage.data)
        }
    }

    // MARK: - Notification Display

    /**
     * Show notification to user
     */
    private fun showNotification(title: String, body: String, data: Map<String, String> = emptyMap()) {
        println("📱 FCM: Showing notification - $title")

        // Create intent to open app using package name (avoids compile-time dependency)
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

            // Add notification data to intent
            data.forEach { (key, value) ->
                putExtra(key, value)
            }
            putExtra("fromNotification", true)
            putExtra("notificationTitle", title)
            putExtra("notificationBody", body)
        } ?: Intent().apply {
            setClassName(packageName, "$packageName.MainActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

            data.forEach { (key, value) ->
                putExtra(key, value)
            }
            putExtra("fromNotification", true)
            putExtra("notificationTitle", title)
            putExtra("notificationBody", body)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID_BASE + System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build notification
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Replace with your app icon
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_BASE + System.currentTimeMillis().toInt(), notification)

        println("✅ FCM: Notification displayed")
    }

    /**
     * Handle data-only push notification payload
     */
    private fun handleDataPayload(data: Map<String, String>) {
        val notificationType = data["type"] ?: return
        val title = data["title"] ?: "Stitch Social"
        val body = data["body"] ?: ""

        println("📱 FCM: Handling data payload - Type: $notificationType")

        // Show notification for data-only messages
        showNotification(title, body, data)

        // Store notification in Firestore (background operation)
        serviceScope.launch {
            try {
                val userID = auth.currentUser?.uid ?: return@launch

                val notificationData = hashMapOf(
                    "type" to notificationType,
                    "title" to title,
                    "body" to body,
                    "payload" to data,
                    "isRead" to false,
                    "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )

                db.collection("users")
                    .document(userID)
                    .collection("notifications")
                    .add(notificationData)
                    .await()

                println("✅ FCM: Notification stored in Firestore")

            } catch (e: Exception) {
                println("❌ FCM: Failed to store notification - ${e.message}")
            }
        }
    }

    // MARK: - Channel Management

    /**
     * Create notification channel for Android O+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for Stitch Social activity"
                enableLights(true)
                enableVibration(true)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            println("✅ FCM: Notification channel created")
        }
    }

    // MARK: - Cleanup

    override fun onDestroy() {
        super.onDestroy()
        println("🔴 FCM SERVICE: Service destroyed")
    }
}

/**
 * FCM Manager for token registration and permissions
 */
object FCMManager {

    /**
     * Initialize FCM and request token
     */
    fun initialize(context: Context) {
        println("📱 FCM MANAGER: Initializing...")
        FCMService.registerFCMToken(context)
    }

    /**
     * Check if FCM is registered
     */
    suspend fun isRegistered(): Boolean {
        return try {
            val token = FirebaseMessaging.getInstance().token.await()
            token.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get current FCM token
     */
    suspend fun getToken(): String? {
        return try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Delete FCM token (for sign out)
     */
    suspend fun deleteToken() {
        try {
            FirebaseMessaging.getInstance().deleteToken().await()
            println("✅ FCM MANAGER: Token deleted")
        } catch (e: Exception) {
            println("❌ FCM MANAGER: Failed to delete token - ${e.message}")
        }
    }

    /**
     * Subscribe to topic
     */
    suspend fun subscribeToTopic(topic: String) {
        try {
            FirebaseMessaging.getInstance().subscribeToTopic(topic).await()
            println("✅ FCM MANAGER: Subscribed to topic: $topic")
        } catch (e: Exception) {
            println("❌ FCM MANAGER: Failed to subscribe to topic - ${e.message}")
        }
    }

    /**
     * Unsubscribe from topic
     */
    suspend fun unsubscribeFromTopic(topic: String) {
        try {
            FirebaseMessaging.getInstance().unsubscribeFromTopic(topic).await()
            println("✅ FCM MANAGER: Unsubscribed from topic: $topic")
        } catch (e: Exception) {
            println("❌ FCM MANAGER: Failed to unsubscribe from topic - ${e.message}")
        }
    }
}