/*
 * ShareButton.kt - SHARE BUTTON (matches iOS ShareButton.swift)
 * STITCH SOCIAL - ANDROID KOTLIN
 */

package com.stitchsocial.club

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesomeMosaic
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.stitchsocial.club.views.ThreadCollageSelectionView
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.ThreadData
import com.stitchsocial.club.services.ShareService
import com.stitchsocial.club.services.ShareableVideo
import com.stitchsocial.club.services.VideoServiceImpl
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "SHARE_BTN"

enum class ShareButtonSize(val iconSize: Dp) {
    TINY(16.dp),   // 32dp button, no label — for the fullscreen top stack
    SMALL(24.dp),
    MEDIUM(28.dp),
    LARGE(32.dp)
}

// MARK: - Share Button

@OptIn(ExperimentalMaterial3Api::class)
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
    var showOptionsSheet by remember { mutableStateOf(false) }

    // Thread Collage state. threadData is loaded lazily when the user
    // picks the collage option so we don't fetch children for every
    // ShareButton on the feed.
    var loadingCollageData by remember { mutableStateOf(false) }
    var collageThreadData by remember { mutableStateOf<ThreadData?>(null) }

    val effectiveThreadID = threadID ?: video.threadID
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                .clickable(enabled = !isSharing && !loadingCollageData) {
                    Log.d(TAG, "🔘 SHARE BUTTON TAPPED — opening options sheet")
                    showOptionsSheet = true
                },
            contentAlignment = Alignment.Center
        ) {
            if (isSharing || loadingCollageData) {
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

        if (size != ShareButtonSize.SMALL && size != ShareButtonSize.TINY) {
            Text(
                if (isSharing || loadingCollageData) "..." else "Share",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
    }

    // ── Options bottom sheet ──────────────────────────────────────────
    if (showOptionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showOptionsSheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF111111),
        ) {
            ShareOptionRow(
                icon = Icons.Default.Share,
                title = "Share Video",
                subtitle = "Send this clip as-is",
                onClick = {
                    showOptionsSheet = false
                    isSharing = true
                    scope.launch {
                        try {
                            // Share/watermark needs a real downloadable MP4 — the
                            // faststart mp4URL (CDN), NOT the HLS .m3u8 playbackURL and
                            // NOT a legacy/empty videoURL. Prefer mp4URL, fall back to videoURL.
                            val downloadURL = video.mp4URL?.takeIf { it.isNotBlank() } ?: video.videoURL
                            val shareable = ShareableVideo(
                                id = video.id,
                                videoURL = downloadURL,
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
                            ShareService.shareVideo(
                                context = context,
                                video = shareable,
                                creatorUsername = creatorUsername,
                                threadID = effectiveThreadID
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Share failed: ${e.message}", e)
                            Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            isSharing = false
                        }
                    }
                }
            )

            ShareOptionRow(
                icon = Icons.Default.AutoAwesomeMosaic,
                title = "Thread Collage",
                subtitle = "Stitch this thread into a 60s share",
                onClick = {
                    showOptionsSheet = false
                    if (effectiveThreadID.isNullOrEmpty()) {
                        Toast.makeText(context, "This video isn't part of a thread", Toast.LENGTH_SHORT).show()
                        return@ShareOptionRow
                    }
                    loadingCollageData = true
                    scope.launch {
                        try {
                            val children = VideoServiceImpl().getThreadChildren(effectiveThreadID)
                            collageThreadData = ThreadData(
                                id = effectiveThreadID,
                                parentVideo = video,
                                childVideos = children,
                                isChildrenLoaded = true,
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Collage load failed: ${e.message}", e)
                            Toast.makeText(context, "Couldn't load thread", Toast.LENGTH_SHORT).show()
                        } finally {
                            loadingCollageData = false
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // ── Thread Collage builder (full-screen dialog) ───────────────────
    val data = collageThreadData
    if (data != null) {
        Dialog(
            onDismissRequest = { collageThreadData = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            )
        ) {
            ThreadCollageSelectionView(
                threadData = data,
                onDismiss = { collageThreadData = null },
                onExportComplete = { exportedUri ->
                    collageThreadData = null
                    fireCollageShareIntent(context, exportedUri, creatorUsername, effectiveThreadID)
                },
            )
        }
    }
}

// ── Share option row used inside the bottom sheet ────────────────────

@Composable
private fun ShareOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        }
    }
}

// ── Collage export → share intent ────────────────────────────────────

/**
 * Bridge the Media3 export URI to a system share sheet. Mirrors the
 * ShareServices.kt FileProvider/ACTION_SEND pattern so the share works
 * across the same set of receiving apps as the regular share path.
 */
private fun fireCollageShareIntent(
    context: android.content.Context,
    exportedUri: Uri,
    creatorUsername: String,
    threadID: String?,
) {
    try {
        // The collage Transformer writes to a File under cacheDir, so
        // exportedUri is a file:// URI. Wrap it via FileProvider to get a
        // shareable content:// URI.
        val path = exportedUri.path ?: run {
            Toast.makeText(context, "Couldn't read exported file", Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(path)
        val contentUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val text = buildString {
            append("Made a Thread Collage with @$creatorUsername on StitchSocial!")
            if (!threadID.isNullOrEmpty()) append("\n\nstitch://thread/$threadID")
            append("\n\nDownload StitchSocial: https://play.google.com/store/apps/details?id=com.stitchsocial.club")
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_TEXT, text)
            // ClipData propagates the read grant to share targets on Android 10+
            // (Samsung One UI included) — without it the receiver gets no video.
            clipData = android.content.ClipData.newUri(context.contentResolver, "StitchSocial collage", contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share Collage")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Grant read URI permission to all chooser targets (mirrors
        // ShareServices.kt — without this some receivers reject the URI).
        val resInfoList = context.packageManager.queryIntentActivities(chooser, 0)
        for (resInfo in resInfoList) {
            val pkg = resInfo.activityInfo.packageName
            context.grantUriPermission(pkg, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(chooser)
    } catch (e: Exception) {
        Log.e(TAG, "❌ Collage share failed: ${e.message}", e)
        Toast.makeText(context, "Couldn't open share sheet", Toast.LENGTH_SHORT).show()
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