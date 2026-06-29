package com.stitchsocial.club.views

import com.stitchsocial.club.ui.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.stitchsocial.club.services.ReportService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ReportSheet - Presented when the user taps "Report" on a video or
 * profile. Collects category + optional note, calls submitReport via
 * ReportService.
 *
 * Server-side, submitReport tracks strikes and auto-suspends at 5
 * substantiated reports per offender.
 *
 * This is the human-flagging counterpart to AWS Rekognition's automated
 * moderation — Rekognition catches what AI can see, this catches what
 * only people can (impersonation, harassment, IP, hate-speech context).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(
    targetType: String,
    targetID: String,
    onDismiss: () -> Unit,
    reportService: ReportService = remember { ReportService() }
) {
    var selectedReason by remember { mutableStateOf<ReportReason?>(null) }
    var note by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    var showSuccess by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(AppTheme.colors.bg),
            color = Color.Black
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppTheme.colors.bg)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = AppTheme.colors.textPrimary)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "Report",
                        color = AppTheme.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // Phantom right widget to balance the title
                    Spacer(modifier = Modifier.width(64.dp))
                }

                Divider(color = AppTheme.colors.surfaceStrong)

                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "What's the issue?",
                        color = AppTheme.colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Our team reviews every report. False reports can affect your standing.",
                        color = AppTheme.colors.textSecondary,
                        fontSize = 13.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Reason chips
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReportReason.values().forEach { reason ->
                            ReasonRow(
                                reason = reason,
                                selected = selectedReason == reason,
                                onClick = { selectedReason = reason }
                            )
                        }
                    }

                    // Note field
                    if (selectedReason != null) {
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            "Add context (optional)",
                            color = AppTheme.colors.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 80.dp, max = 160.dp)
                                .background(
                                    AppTheme.colors.surface,
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(12.dp)
                        ) {
                            if (note.isEmpty()) {
                                Text(
                                    "What should we know?",
                                    color = AppTheme.colors.textSecondary.copy(alpha = 0.6f),
                                    fontSize = 15.sp
                                )
                            }
                            BasicTextField(
                                value = note,
                                onValueChange = { if (it.length <= 1000) note = it },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = TextStyle(color = AppTheme.colors.textPrimary, fontSize = 15.sp),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White)
                            )
                        }
                        Text(
                            "${note.length} / 1000",
                            color = AppTheme.colors.textSecondary.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(top = 4.dp)
                        )
                    }

                    submitError?.let {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(it, color = Color.Red, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Submit
                    Button(
                        onClick = {
                            val reason = selectedReason ?: return@Button
                            scope.launch {
                                isSubmitting = true
                                submitError = null
                                try {
                                    reportService.submitReport(
                                        targetType = targetType,
                                        targetID = targetID,
                                        reason = reason.serverKey,
                                        note = note.takeIf { it.isNotBlank() }
                                    )
                                    showSuccess = true
                                    delay(1400)
                                    onDismiss()
                                } catch (e: Exception) {
                                    submitError = e.message ?: "Couldn't submit report. Try again."
                                } finally {
                                    isSubmitting = false
                                }
                            }
                        },
                        enabled = selectedReason != null && !isSubmitting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedReason != null) Color.White else AppTheme.colors.textSecondary.copy(alpha = 0.3f),
                            contentColor = Color.Black,
                            disabledContainerColor = AppTheme.colors.textSecondary.copy(alpha = 0.3f),
                            disabledContentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(
                                color = Color.Black,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            if (isSubmitting) "Sending…" else "Submit report",
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "If someone is in immediate danger, contact local emergency services. For DMCA takedowns, email dmca@stitchsocial.me.",
                        color = AppTheme.colors.textSecondary.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            // Success toast
            if (showSuccess) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 16.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text(
                        "Report sent. Thanks for keeping Stitch safe.",
                        color = AppTheme.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .background(Color(0xCC1E8E3E), RoundedCornerShape(20.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReasonRow(
    reason: ReportReason,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) Color.White else AppTheme.colors.surfaceStrong,
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = reason.icon,
            contentDescription = null,
            tint = if (selected) Color.Black else Color.White,
            modifier = Modifier.size(20.dp)
        )
        Text(
            reason.label,
            color = if (selected) Color.Black else Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

enum class ReportReason(
    val label: String,
    val serverKey: String,
    val icon: ImageVector
) {
    NUDITY_SEXUAL("Nudity or sexual content", ReportService.REASON_ADULT, Icons.Default.Shield),
    VIOLENCE_GRAPHIC("Graphic violence", ReportService.REASON_VIOLENCE, Icons.Default.Warning),
    HATE_SPEECH("Hate speech or extremism", ReportService.REASON_HATE, Icons.Default.PersonOff),
    COPYRIGHT_IP("Copyright or trademark", ReportService.REASON_IP_INFRINGEMENT, Icons.Default.Copyright),
    SPAM_SCAM("Spam or scam", ReportService.REASON_SPAM, Icons.Default.Block),
    IMPERSONATION("Impersonation", ReportService.REASON_IMPERSONATION, Icons.Default.PersonSearch),
    MINOR_SAFETY("Child safety violation", ReportService.REASON_MINOR_SAFETY, Icons.Default.Shield),
    OTHER("Something else", ReportService.REASON_OTHER, Icons.Default.Flag);
}
