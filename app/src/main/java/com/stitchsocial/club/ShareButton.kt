/*
 * ShareButton.kt - SHARE BUTTON (matches iOS ShareButton.swift)
 * STITCH SOCIAL - ANDROID KOTLIN
 */

package com.stitchsocial.club

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.services.ShareService
import com.stitchsocial.club.services.ShareableVideo
import kotlinx.coroutines.launch

private const val TAG = "SHARE_BTN"

enum class ShareButtonSize(val iconSize: Dp) {
    SMALL(24.dp),
    MEDIUM(28.dp),
    LARGE(32.dp)
}

// MARK: - Share Button

@Composable
fun ShareButton(
    video: CoreVideoMetadata,
    creatorUsername: String,
    threadID: String? = null,
    size: ShareButtonSize = ShareButtonSize.MEDIUM
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSharing by remember { mutableStateOf(false) }

    val buttonSize = size.iconSize + 16.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(buttonSize)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.3f))
                .clickable(enabled = !isSharing) {
                    Log.d(TAG, "🔘 SHARE BUTTON TAPPED!")
                    Log.d(TAG, "🔘 Video ID: ${video.id}")
                    Log.d(TAG, "🔘 Video URL: ${video.videoURL}")
                    Log.d(TAG, "🔘 Creator: $creatorUsername")

                    isSharing = true

                    scope.launch {
                        try {
                            Log.d(TAG, "🔘 Launching share coroutine...")
                            Toast.makeText(context, "Share: starting...", Toast.LENGTH_SHORT).show()

                            val shareable = ShareableVideo(
                                id = video.id,
                                videoURL = video.videoURL,
                                thumbnailURL = video.thumbnailURL,
                                title = video.title,
                                creatorID = video.creatorID,
                                creatorName = creatorUsername,
                                threadID = video.threadID,
                                hypeCount = video.hypeCount,
                                coolCount = video.coolCount,
                                viewCount = video.viewCount,
                                temperature = video.temperature.name.lowercase()
                            )

                            Toast.makeText(context, "URL: ${video.videoURL.take(50)}", Toast.LENGTH_LONG).show()
                            Log.d(TAG, "🔘 Video URL: ${video.videoURL}")

                            ShareService.shareVideo(
                                context = context,
                                video = shareable,
                                creatorUsername = creatorUsername,
                                threadID = threadID ?: video.threadID
                            )

                            Log.d(TAG, "✅ ShareService.shareVideo completed")

                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Share failed: ${e.message}", e)
                            Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            isSharing = false
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (isSharing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(size.iconSize),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "Share",
                    tint = Color.White,
                    modifier = Modifier.size(size.iconSize)
                )
            }
        }

        if (size != ShareButtonSize.SMALL) {
            Text(
                if (isSharing) "..." else "Share",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}

// MARK: - Share Export Overlay

@Composable
fun ShareExportOverlay() {
    val isExporting by ShareService.isExporting.collectAsState()
    val progress by ShareService.exportProgress.collectAsState()

    if (isExporting) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .background(
                        Color.Black.copy(alpha = 0.8f),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = Color.White,
                    strokeWidth = 3.dp
                )

                Text(
                    progress,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Text(
                    "This may take a moment...",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}