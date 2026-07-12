package com.stitchsocial.club.events

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.stitchsocial.club.BuildConfig
import kotlinx.coroutines.delay

/**
 * Event HUD — overlay card on the event thread HEAD (stacks below the
 * Contest HUD when both are present). Calendar + name, dateLine · whereLine,
 * a live status chip (LIVE NOW / ENDED / "in {countdown}"), an orange RSVP
 * capsule (ACTION_VIEW) and an "Interested" toggle that schedules a local
 * notification 1 hour before start via EventReminderWorker.
 */
@Composable
fun EventHUD(
    videoId: String,
    event: StitchEvent,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val orange = Color(0xFFFF9800)
    val cyan = Color(0xFF00E5FF)
    val liveRed = Color(0xFFFF3B30)

    // Interested state persists locally so the toggle survives recomposition/relaunch.
    val prefs = remember { context.getSharedPreferences("event_reminders", Context.MODE_PRIVATE) }
    var interested by remember(videoId) { mutableStateOf(prefs.getBoolean(videoId, false)) }

    // 1s tick drives the status chip / countdown.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(event.startAt) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val started = nowMs >= event.startAt.time
    val ended = nowMs >= event.effectiveEnd.time

    fun markInterested() {
        EventReminderWorker.schedule(context, videoId, event)
        prefs.edit().putBoolean(videoId, true).apply()
        interested = true
        if (BuildConfig.DEBUG) { println("📅 EVENT: reminder scheduled for $videoId (${event.name})") }
    }

    // Graceful POST_NOTIFICATIONS ask (API 33+). Denied? Still schedule — the
    // worker no-ops without permission, and re-granting later makes it work.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (BuildConfig.DEBUG && !granted) { println("📅 EVENT: POST_NOTIFICATIONS denied — reminder scheduled but silent") }
        markInterested()
    }

    Column(
        modifier = modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, orange.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Header: calendar + event name
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Default.CalendarMonth,
                contentDescription = null,
                tint = orange,
                modifier = Modifier.size(16.dp)
            )
            Text(
                event.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        // When · where
        Text(
            listOf(event.dateLine, event.whereLine)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        // Status chip
        val (chipText, chipColor) = when {
            ended -> "ENDED" to Color.Gray
            started -> "LIVE NOW" to liveRed
            else -> "in ${StitchEvent.countdownText(event.startAt.time - nowMs)}" to cyan
        }
        Text(
            chipText,
            color = chipColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(CircleShape)
                .background(chipColor.copy(alpha = 0.18f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )

        // Actions: RSVP capsule + Interested toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val rsvp = event.rsvpURL
            if (!rsvp.isNullOrBlank() && !ended) {
                Text(
                    "RSVP",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(orange)
                        .clickable {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rsvp)))
                            } catch (e: Exception) {
                                if (BuildConfig.DEBUG) { println("📅 EVENT: could not open rsvpURL — ${e.message}") }
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                )
            }

            if (!started && !ended) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (interested) cyan.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.12f))
                        .border(
                            1.dp,
                            if (interested) cyan.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.25f),
                            CircleShape
                        )
                        .clickable {
                            if (interested) {
                                EventReminderWorker.cancel(context, videoId)
                                prefs.edit().remove(videoId).apply()
                                interested = false
                            } else {
                                val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                                    ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                if (needsPermission) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    markInterested()
                                }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(
                        if (interested) Icons.Default.NotificationsActive else Icons.Default.Notifications,
                        contentDescription = null,
                        tint = if (interested) cyan else Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        if (interested) "Reminder set" else "Interested",
                        color = if (interested) cyan else Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
