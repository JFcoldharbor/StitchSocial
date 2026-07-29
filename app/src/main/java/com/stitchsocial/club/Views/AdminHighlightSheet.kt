/*
 * AdminHighlightSheet.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views — admin-only "highlight this video" composer.
 *
 * Spotlights any video as the announcement users see when they open the app —
 * a Video of the Day / Stitch Moment rather than an app notice.
 *
 * This deliberately rides the EXISTING announcement machinery instead of adding
 * a parallel system: Announcement already carried a videoId, the overlay already
 * plays it on launch with per-user seen/dismiss tracking, and the Firestore rule
 * on /announcements already restricts writes to the admin emails. All that was
 * missing was a type (HIGHLIGHT) and a way to publish one from a video.
 *
 * Visibility is gated on AnnouncementService.isAuthorizedCreator, which mirrors
 * the server rule — so a non-admin never sees the entry point, and the server
 * still refuses the write if anyone gets there another way.
 */

package com.stitchsocial.club.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.services.AnnouncementService
import kotlinx.coroutines.launch

private object AH {
    val card = Color(0xFF1A1432)
    val border = Color.White.copy(alpha = 0.10f)
    val gold = Color(0xFFFACC15)
    val pink = Color(0xFFF0245F)
    val txt = Color(0xFFF1F5F9)
    val txt2 = Color(0xFF94A3B8)
    val txt3 = Color(0xFF64748B)
}

@Composable
fun AdminHighlightSheet(
    videoId: String,
    videoTitle: String,
    adminEmail: String,
    adminUserId: String,
    onDismiss: () -> Unit,
    onPublished: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val service = remember { AnnouncementService.shared }

    var preset by remember { mutableStateOf(AnnouncementService.HighlightPreset.VIDEO_OF_THE_DAY) }
    var customLabel by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var durationHours by remember { mutableStateOf(24) }
    var publishing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    val label = customLabel.trim().ifBlank { preset.label }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .pointerInput(Unit) { detectTapGestures(onTap = { if (!publishing) onDismiss() }) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(20.dp))
                .background(AH.card)
                .border(0.5.dp, AH.border, RoundedCornerShape(20.dp))
                .pointerInput(Unit) { detectTapGestures(onTap = { }) }
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Star, null, tint = AH.gold, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Highlight this video", color = AH.txt, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    Text(
                        "Shows to everyone when they open Stitch",
                        color = AH.txt2,
                        fontSize = 11.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.1f))
                        .clickable(enabled = !publishing) { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(13.dp))
                }
            }

            Spacer(Modifier.height(14.dp))

            if (videoTitle.isNotBlank()) {
                Text(
                    videoTitle,
                    color = AH.txt2,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
                Spacer(Modifier.height(14.dp))
            }

            if (done) {
                Text("★ Highlighted", color = AH.gold, fontSize = 14.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(6.dp))
                Text(
                    "This replaces any previous highlight — only one runs at a time.",
                    color = AH.txt2,
                    fontSize = 11.sp,
                )
                return@Column
            }

            SectionLabel("LABEL")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnnouncementService.HighlightPreset.values().forEach { p ->
                    val selected = preset == p && customLabel.isBlank()
                    Text(
                        p.label,
                        color = if (selected) Color.Black else AH.txt,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (selected) AH.gold else Color.White.copy(alpha = 0.08f))
                            .clickable { preset = p; customLabel = "" }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Field(
                value = customLabel,
                onChange = { if (it.length <= 40) customLabel = it },
                placeholder = "…or a custom label",
            )

            Spacer(Modifier.height(14.dp))
            SectionLabel("NOTE (OPTIONAL)")
            Field(
                value = message,
                onChange = { if (it.length <= 140) message = it },
                placeholder = "Why this one?",
            )

            Spacer(Modifier.height(14.dp))
            SectionLabel("RUNS FOR")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(24 to "24 hours", 48 to "2 days", 168 to "A week").forEach { (h, text) ->
                    val selected = durationHours == h
                    Text(
                        text,
                        color = if (selected) Color.Black else AH.txt,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (selected) Color.White else Color.White.copy(alpha = 0.08f))
                            .clickable { durationHours = h }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = AH.pink, fontSize = 11.sp)
            }

            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AH.gold)
                    .clickable(enabled = !publishing) {
                        publishing = true
                        error = null
                        scope.launch {
                            runCatching {
                                service.createHighlight(
                                    videoId = videoId,
                                    creatorEmail = adminEmail,
                                    creatorId = adminUserId,
                                    label = label,
                                    message = message.trim().ifBlank { null },
                                    durationHours = durationHours,
                                )
                            }.onSuccess {
                                publishing = false
                                done = true
                                onPublished()
                            }.onFailure { e ->
                                publishing = false
                                error = e.localizedMessage ?: "Couldn't publish that highlight"
                            }
                        }
                    }
                    .padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (publishing) {
                    CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(15.dp))
                } else {
                    Text("Publish highlight", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Black)
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Replaces the current highlight — only one runs at a time.",
                color = AH.txt3,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.4f),
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = AH.txt, fontSize = 13.sp),
        cursorBrush = SolidColor(AH.gold),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                    .border(0.5.dp, AH.border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, color = AH.txt3, fontSize = 13.sp)
                inner()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
