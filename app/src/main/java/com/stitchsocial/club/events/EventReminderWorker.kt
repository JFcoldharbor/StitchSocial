package com.stitchsocial.club.events

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.stitchsocial.club.R
import java.util.concurrent.TimeUnit

/**
 * One-shot local "event starts in 1 hour" reminder, scheduled from the
 * EventHUD "Interested" toggle. WorkManager (not AlarmManager) so we need
 * no manifest receiver and no exact-alarm permission — minute-level drift
 * is fine for a 1-hour heads-up.
 *
 * Unique work name: "event_reminder_{videoId}" — toggling off cancels it.
 */
class EventReminderWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val eventName = inputData.getString(KEY_EVENT_NAME) ?: return Result.success()
        val whereLine = inputData.getString(KEY_WHERE_LINE) ?: ""
        val videoId = inputData.getString(KEY_VIDEO_ID) ?: ""

        // Graceful: if notifications were revoked after scheduling, just no-op.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Event Reminders",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Reminders for events you're interested in" }
            )
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("Starting in 1 hour: $eventName")
            .setContentText(if (whereLine.isNotBlank()) whereLine else "Tap Stitch for details")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(NOTIFICATION_TAG, videoId.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission race — nothing to do.
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "event_reminders"
        private const val NOTIFICATION_TAG = "event_reminder"
        private const val KEY_EVENT_NAME = "eventName"
        private const val KEY_WHERE_LINE = "whereLine"
        private const val KEY_VIDEO_ID = "videoId"

        private fun uniqueName(videoId: String) = "event_reminder_$videoId"

        /**
         * Schedule (or reschedule) the 1h-before reminder. If the event starts
         * in under an hour, fires immediately; if it already started, no-op.
         */
        fun schedule(context: Context, videoId: String, event: StitchEvent) {
            val fireInMs = event.timeUntilStart - TimeUnit.HOURS.toMillis(1)
            if (event.timeUntilStart <= 0) return  // already started — nothing to remind

            val request = OneTimeWorkRequestBuilder<EventReminderWorker>()
                .setInitialDelay(fireInMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(
                    Data.Builder()
                        .putString(KEY_EVENT_NAME, event.name)
                        .putString(KEY_WHERE_LINE, event.whereLine)
                        .putString(KEY_VIDEO_ID, videoId)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(videoId),
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancel(context: Context, videoId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(videoId))
        }
    }
}
