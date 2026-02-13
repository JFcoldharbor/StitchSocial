/*
 * FCMService.kt - FIREBASE CLOUD MESSAGING SERVICE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services - Handle FCM push notifications
 * Dependencies: Firebase Messaging, Notification Service
 * Features: Token registration, background notifications, notification handling
 *
 * EXACT PORT: FCMPushManager.swift functionality for Android
 * ✅ FIXED: Uses ic_stat_name for notification icon
 * ✅ FIXED: Lock screen visibility + heads-up popup
 * ✅ FIXED: Channel created with IMPORTANCE_HIGH for popup
 * ✅ FIXED: Early channel creation via ensureChannelExists()
 */

package com.stitchsocial.club.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.stitchsocial.club.R
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
         * ✅ CALL THIS FROM MainActivity.onCreate() to ensure channel exists early
         * Must be called before any notification is sent
         */
        fun ensureChannelExists(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH // ✅ Required for heads-up popup
                ).apply {
                    description = "Notifications for Stitch Social activity"
                    enableLights(true)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 250, 250)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC // ✅ Lock screen
                    setSound(
                        soundUri,
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .build()
                    )
                    setShowBadge(true) // ✅ App icon badge
                }

                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
                println("✅ FCM: Notification channel created/verified")
            }
        }

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

                db.collection("user_tokens")
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
        ensureChannelExists(this)
        println("📱 FCM SERVICE: Service created")
    }

    // MARK: - Token Management

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

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        println("📱 FCM: Message received")
        println("📱 FCM: From: ${remoteMessage.from}")
        println("📱 FCM: Data: ${remoteMessage.data}")

        // Handle notification payload (foreground)
        remoteMessage.notification?.let { notification ->
            println("📱 FCM: Notification - ${notification.title}: ${notification.body}")
            showNotification(
                title = notification.title ?: "Stitch Social",
                body = notification.body ?: "",
                data = remoteMessage.data
            )
        }

        // Handle data-only payload (no notification field)
        if (remoteMessage.notification == null && remoteMessage.data.isNotEmpty()) {
            println("📱 FCM: Data-only payload - ${remoteMessage.data}")
            handleDataPayload(remoteMessage.data)
        }
    }

    // MARK: - Notification Display

    private fun showNotification(title: String, body: String, data: Map<String, String> = emptyMap()) {
        println("📱 FCM: Building notification - $title")

        // Ensure channel exists before every notification
        ensureChannelExists(this)

        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data.forEach { (key, value) -> putExtra(key, value) }
            putExtra("fromNotification", true)
            putExtra("notificationTitle", title)
            putExtra("notificationBody", body)
        } ?: Intent().apply {
            setClassName(packageName, "$packageName.MainActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data.forEach { (key, value) -> putExtra(key, value) }
            putExtra("fromNotification", true)
            putExtra("notificationTitle", title)
            putExtra("notificationBody", body)
        }

        val notificationId = NOTIFICATION_ID_BASE + (System.currentTimeMillis() % 10000).toInt()

        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_stat_name)               // ✅ Your Stitch logo
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)        // ✅ Heads-up popup
            .setDefaults(NotificationCompat.DEFAULT_ALL)          // ✅ Sound + vibrate + lights
            .setSound(defaultSoundUri)                            // ✅ Notification sound
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)  // ✅ Show on lock screen
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)      // ✅ Social category
            .setNumber(1)                                         // ✅ Badge count
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)

        println("✅ FCM: Notification displayed (id=$notificationId)")
    }

    /**
     * Handle data-only push notification payload
     */
    private fun handleDataPayload(data: Map<String, String>) {
        val notificationType = data["type"] ?: return
        val title = data["title"] ?: "Stitch Social"
        val body = data["body"] ?: ""

        println("📱 FCM: Handling data payload - Type: $notificationType")
        showNotification(title, body, data)
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

    fun initialize(context: Context) {
        println("📱 FCM MANAGER: Initializing...")
        // ✅ Create channel early so system-delivered notifications work
        FCMService.ensureChannelExists(context)
        FCMService.registerFCMToken(context)
    }

    suspend fun isRegistered(): Boolean {
        return try {
            val token = FirebaseMessaging.getInstance().token.await()
            token.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getToken(): String? {
        return try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun deleteToken() {
        try {
            FirebaseMessaging.getInstance().deleteToken().await()
            println("✅ FCM MANAGER: Token deleted")
        } catch (e: Exception) {
            println("❌ FCM MANAGER: Failed to delete token - ${e.message}")
        }
    }

    suspend fun subscribeToTopic(topic: String) {
        try {
            FirebaseMessaging.getInstance().subscribeToTopic(topic).await()
            println("✅ FCM MANAGER: Subscribed to topic: $topic")
        } catch (e: Exception) {
            println("❌ FCM MANAGER: Failed to subscribe to topic - ${e.message}")
        }
    }

    suspend fun unsubscribeFromTopic(topic: String) {
        try {
            FirebaseMessaging.getInstance().unsubscribeFromTopic(topic).await()
            println("✅ FCM MANAGER: Unsubscribed from topic: $topic")
        } catch (e: Exception) {
            println("❌ FCM MANAGER: Failed to unsubscribe from topic - ${e.message}")
        }
    }
}