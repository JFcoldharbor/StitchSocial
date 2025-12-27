/*
 * ShareService.kt - VIDEO SHARING SERVICE
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Services - Video Sharing
 * Dependencies: Android Context, MediaStore
 * Features: Share videos to other apps with deep links
 *
 * STANDALONE SERVICE - Integrate with existing app
 */

package com.stitchsocial.club.services

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.UUID

/**
 * Video metadata for sharing
 */
data class ShareableVideo(
    val id: String,
    val videoURL: String,
    val thumbnailURL: String,
    val title: String,
    val creatorID: String,
    val creatorName: String,
    val threadID: String?
)

/**
 * Share result status
 */
sealed class ShareResult {
    object Success : ShareResult()
    data class Error(val message: String) : ShareResult()
}

/**
 * Service to handle sharing videos to other apps
 * Singleton pattern for global access
 */
object ShareService {

    // MARK: - State

    private val _isExporting: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting

    private val _exportProgress: MutableStateFlow<String> = MutableStateFlow("")
    val exportProgress: StateFlow<String> = _exportProgress

    // Cache directory for temp files
    private var cacheDir: File? = null

    // MARK: - Initialization

    /**
     * Initialize the share service with context
     */
    fun initialize(context: Context) {
        cacheDir = context.cacheDir
    }

    // MARK: - Share Video

    /**
     * Share a video with watermark
     *
     * @param context Android context
     * @param video Video metadata to share
     * @param creatorUsername Username of the video creator
     * @param threadID Optional thread ID for deep linking
     */
    suspend fun shareVideo(
        context: Context,
        video: ShareableVideo,
        creatorUsername: String,
        threadID: String? = null
    ): ShareResult = withContext(Dispatchers.IO) {
        try {
            _isExporting.value = true
            _exportProgress.value = "Preparing video..."

            // Download video if remote
            val localFile: File = downloadVideoIfNeeded(video.videoURL)
                ?: return@withContext ShareResult.Error("Failed to download video")

            _exportProgress.value = "Ready to share!"

            // Create share intent
            withContext(Dispatchers.Main) {
                presentShareSheet(
                    context = context,
                    videoFile = localFile,
                    video = video,
                    creatorUsername = creatorUsername,
                    threadID = threadID
                )
            }

            _isExporting.value = false
            _exportProgress.value = ""

            ShareResult.Success
        } catch (e: Exception) {
            _isExporting.value = false
            _exportProgress.value = ""
            ShareResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Quick share without watermark
     */
    suspend fun quickShare(
        context: Context,
        video: ShareableVideo,
        creatorUsername: String,
        threadID: String? = null
    ): ShareResult = withContext(Dispatchers.IO) {
        try {
            _isExporting.value = true
            _exportProgress.value = "Preparing..."

            val localFile: File = downloadVideoIfNeeded(video.videoURL)
                ?: return@withContext ShareResult.Error("Failed to download video")

            withContext(Dispatchers.Main) {
                presentShareSheet(
                    context = context,
                    videoFile = localFile,
                    video = video,
                    creatorUsername = creatorUsername,
                    threadID = threadID
                )
            }

            _isExporting.value = false
            _exportProgress.value = ""

            ShareResult.Success
        } catch (e: Exception) {
            _isExporting.value = false
            _exportProgress.value = ""
            ShareResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    // MARK: - Download Video

    private suspend fun downloadVideoIfNeeded(urlString: String): File? = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)

            // Check if it's a local file
            if (urlString.startsWith("file://")) {
                return@withContext File(Uri.parse(urlString).path ?: return@withContext null)
            }

            println("📥 SHARE: Downloading video...")

            // Download to temp file
            val tempFile = File(
                cacheDir ?: return@withContext null,
                "download_${UUID.randomUUID()}.mp4"
            )

            url.openStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            println("✅ SHARE: Video downloaded to ${tempFile.absolutePath}")
            tempFile
        } catch (e: Exception) {
            println("❌ SHARE: Download failed - ${e.localizedMessage}")
            null
        }
    }

    // MARK: - Present Share Sheet

    private fun presentShareSheet(
        context: Context,
        videoFile: File,
        video: ShareableVideo,
        creatorUsername: String,
        threadID: String?
    ) {
        // Get content URI via FileProvider
        val contentUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            videoFile
        )

        // Create share text with deep link
        val shareText: String = buildString {
            append("Check out this video by @$creatorUsername on StitchSocial!")
            append("\n\n")

            threadID?.let {
                append("stitch://thread/$it")
                append("\n\n")
            }

            append("Download StitchSocial: https://play.google.com/store/apps/details?id=com.stitchsocial.club")
        }

        // Create share intent
        val shareIntent: Intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_TEXT, shareText)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // Create chooser
        val chooserIntent: Intent = Intent.createChooser(shareIntent, "Share Video")
        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        context.startActivity(chooserIntent)
        println("📤 SHARE: Presenting share sheet")
    }

    // MARK: - Share Link Only

    /**
     * Share just the link without video file
     */
    fun shareLink(
        context: Context,
        video: ShareableVideo,
        creatorUsername: String,
        threadID: String? = null
    ) {
        val shareText: String = buildString {
            append("Check out this video by @$creatorUsername on StitchSocial!")
            append("\n\n")
            append(video.title)
            append("\n\n")

            threadID?.let {
                append("stitch://thread/$it")
                append("\n\n")
            }

            append("Download StitchSocial: https://play.google.com/store/apps/details?id=com.stitchsocial.club")
        }

        val shareIntent: Intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }

        val chooserIntent: Intent = Intent.createChooser(shareIntent, "Share Link")
        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        context.startActivity(chooserIntent)
    }

    // MARK: - Cleanup

    /**
     * Clean up temporary files
     */
    fun cleanup() {
        cacheDir?.listFiles()?.filter { it.name.startsWith("download_") }?.forEach { file: File ->
            try {
                file.delete()
                println("🧹 SHARE: Cleaned up ${file.name}")
            } catch (e: Exception) {
                println("⚠️ SHARE: Failed to delete ${file.name}")
            }
        }
    }

    /**
     * Clean up specific file
     */
    fun cleanupFile(file: File) {
        try {
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            println("⚠️ SHARE: Failed to delete file")
        }
    }
}