/*
 * ShareServices.kt - VIDEO SHARING (matches iOS ShareService.swift)
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Services - Video Sharing
 * Flow: Download actual video file → Share video file + text via chooser
 *
 * NOTE: Watermark not yet applied — requires OpenGL pipeline.
 * The video FILE is shared (not just text). iOS equivalent of
 * ShareService.shared.shareVideo() minus the watermark step.
 *
 * CACHING: Download cache by URL hash avoids re-downloading.
 * Temp files cleaned on app stop.
 */

package com.stitchsocial.club.services

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import com.google.firebase.storage.FirebaseStorage
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class ShareableVideo(
    val id: String,
    val videoURL: String,
    val thumbnailURL: String,
    val title: String,
    val creatorID: String,
    val creatorName: String,
    val threadID: String?,
    val hypeCount: Int = 0,
    val coolCount: Int = 0,
    val viewCount: Int = 0,
    val temperature: String = "warm"
)

sealed class ShareResult {
    object Success : ShareResult()
    data class Error(val message: String) : ShareResult()
}

object ShareService {

    private const val TAG = "SHARE"

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting

    private val _exportProgress = MutableStateFlow("")
    val exportProgress: StateFlow<String> = _exportProgress

    private var appContext: Context? = null
    private var currentShareFile: File? = null

    // CACHING: URL hash → local file
    private val downloadCache = mutableMapOf<String, File>()

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    // MARK: - Share Video (matches iOS ShareService.shareVideo)

    suspend fun shareVideo(
        context: Context,
        video: ShareableVideo,
        creatorUsername: String,
        threadID: String? = null
    ): ShareResult = withContext(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) {
                _isExporting.value = true
                _exportProgress.value = "Preparing video..."
            }

            Log.d(TAG, "📤 Starting share for video: ${video.id}")
            Log.d(TAG, "📤 Video URL: ${video.videoURL}")
            Log.d(TAG, "📤 Calling downloadVideo...")

            // Step 1: Get local video file
            val localFile = downloadVideo(context, video.videoURL)

            Log.d(TAG, "📤 downloadVideo returned: ${localFile?.absolutePath} size=${localFile?.length() ?: 0}")

            if (localFile == null || !localFile.exists() || localFile.length() == 0L) {
                Log.e(TAG, "❌ Download failed - file=${localFile?.absolutePath} exists=${localFile?.exists()} size=${localFile?.length()}")
                // Fallback: share link only
                withContext(Dispatchers.Main) {
                    _isExporting.value = false
                    _exportProgress.value = ""
                    shareLinkFallback(context, video, creatorUsername, threadID)
                }
                return@withContext ShareResult.Success
            }

            Log.d(TAG, "✅ Video file ready: ${localFile.absolutePath} (${localFile.length() / 1024}KB)")
            currentShareFile = localFile

            // Step 2: Save to gallery (on IO thread — file is 30MB+)
            Log.d(TAG, "💾 Attempting gallery save...")
            try {
                saveToGallery(context, localFile, creatorUsername)
                Log.d(TAG, "💾 Gallery save completed")
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ Gallery save failed (non-fatal): ${e.message}", e)
            }

            // Step 3: Open share sheet (must be on Main thread)
            withContext(Dispatchers.Main) {
                _isExporting.value = false
                _exportProgress.value = ""

                shareVideoFile(
                    context = context,
                    videoFile = localFile,
                    creatorUsername = creatorUsername,
                    threadID = threadID ?: video.threadID
                )
            }

            ShareResult.Success

        } catch (e: Exception) {
            Log.e(TAG, "❌ Share error: ${e.message}", e)
            withContext(Dispatchers.Main) {
                _isExporting.value = false
                _exportProgress.value = ""
            }
            ShareResult.Error(e.localizedMessage ?: "Share failed")
        }
    }

    // MARK: - Share Video File via Intent

    private fun shareVideoFile(
        context: Context,
        videoFile: File,
        creatorUsername: String,
        threadID: String?
    ) {
        try {
            // FileProvider URI for share sheet (gallery save already done on IO thread)
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                videoFile
            )

            Log.d(TAG, "📦 FileProvider URI: $contentUri")

            // Share text with deep link
            val shareText = buildString {
                append("Check out this video by @$creatorUsername on StitchSocial!")
                threadID?.let { append("\n\nstitch://thread/$it") }
                append("\n\nDownload StitchSocial: https://play.google.com/store/apps/details?id=com.stitchsocial.club")
            }

            // Intent with BOTH video file AND text
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_TEXT, shareText)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // Grant URI permission to all potential receivers
            val chooser = Intent.createChooser(shareIntent, "Share Video")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Grant to all chooser targets
            val resInfoList = context.packageManager.queryIntentActivities(chooser, 0)
            for (resolveInfo in resInfoList) {
                val packageName = resolveInfo.activityInfo.packageName
                context.grantUriPermission(
                    packageName, contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            context.startActivity(chooser)
            Log.d(TAG, "📤 Share sheet presented with video file")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to present share sheet: ${e.message}", e)
            // Final fallback: share text only
            shareLinkFallback(context, null, creatorUsername, threadID)
        }
    }

    // MARK: - Save to Gallery

    /**
     * Saves video to device gallery via MediaStore.
     * Works on API 29+ (scoped storage) and older versions.
     * CACHING NOTE: The downloaded file is already cached by URL hash,
     * so repeated shares of the same video won't re-download.
     * Gallery save uses MediaStore which handles deduplication by filename.
     */
    private fun saveToGallery(context: Context, videoFile: File, creatorUsername: String) {
        try {
            val filename = "StitchSocial_${creatorUsername}_${System.currentTimeMillis()}.mp4"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+ — use MediaStore (no WRITE_EXTERNAL_STORAGE needed)
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/StitchSocial")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { output ->
                        FileInputStream(videoFile).use { input ->
                            input.copyTo(output, bufferSize = 8192)
                        }
                    }

                    // Mark as complete
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)

                    Log.d(TAG, "💾 Saved to gallery: Movies/StitchSocial/$filename")
                } else {
                    Log.e(TAG, "⚠️ MediaStore insert returned null")
                }
            } else {
                // API 28 and below — copy to Movies folder directly
                val moviesDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "StitchSocial"
                )
                moviesDir.mkdirs()

                val destFile = File(moviesDir, filename)
                videoFile.copyTo(destFile, overwrite = true)

                // Notify media scanner
                val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                mediaScanIntent.data = Uri.fromFile(destFile)
                context.sendBroadcast(mediaScanIntent)

                Log.d(TAG, "💾 Saved to gallery (legacy): ${destFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Gallery save failed (non-fatal): ${e.message}")
            // Non-fatal — share sheet still opens even if gallery save fails
        }
    }

    // MARK: - Download Video

    /**
     * Downloads video from URL to local cache file.
     * Tries Firebase Storage SDK first (handles auth tokens),
     * falls back to HttpURLConnection for non-Firebase URLs.
     * CACHING: Cached by URL hash to avoid re-downloading.
     */
    private suspend fun downloadVideo(context: Context, urlString: String): File? = withContext(Dispatchers.IO) {
        try {
            // Already a local file
            if (urlString.startsWith("file://") || urlString.startsWith("/")) {
                val path = if (urlString.startsWith("file://")) {
                    Uri.parse(urlString).path
                } else urlString
                val file = File(path ?: return@withContext null)
                if (file.exists()) {
                    Log.d(TAG, "✅ Local file: $path")
                    return@withContext file
                }
                return@withContext null
            }

            // Content URI — copy to temp file
            if (urlString.startsWith("content://")) {
                val tempFile = createTempVideoFile(context)
                val uri = Uri.parse(urlString)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output, bufferSize = 8192)
                    }
                }
                if (tempFile.exists() && tempFile.length() > 0) {
                    Log.d(TAG, "✅ Copied content URI: ${tempFile.length() / 1024}KB")
                    return@withContext tempFile
                }
                return@withContext null
            }

            // Check download cache
            val cacheKey = urlString.hashCode().toString()
            val cached = downloadCache[cacheKey]
            if (cached != null && cached.exists() && cached.length() > 0) {
                Log.d(TAG, "✅ Cache hit: ${cached.length() / 1024}KB")
                return@withContext cached
            }

            val tempFile = createTempVideoFile(context)

            // Try Firebase Storage SDK first (handles gs:// and firebasestorage.googleapis.com)
            if (urlString.startsWith("gs://") || urlString.contains("firebasestorage.googleapis.com")) {
                Log.d(TAG, "📥 Downloading via Firebase Storage SDK...")

                withContext(Dispatchers.Main) {
                    _exportProgress.value = "Downloading video..."
                }

                try {
                    val storageRef = if (urlString.startsWith("gs://")) {
                        FirebaseStorage.getInstance().getReferenceFromUrl(urlString)
                    } else {
                        FirebaseStorage.getInstance().getReferenceFromUrl(urlString)
                    }

                    // Download to local file (max 100MB)
                    storageRef.getFile(tempFile).await()

                    if (tempFile.exists() && tempFile.length() > 0) {
                        downloadCache[cacheKey] = tempFile
                        Log.d(TAG, "✅ Firebase download complete: ${tempFile.length() / 1024}KB")
                        return@withContext tempFile
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "⚠️ Firebase Storage download failed, trying HTTP: ${e.message}")
                    // Fall through to HTTP download
                }
            }

            // HTTP download fallback
            Log.d(TAG, "📥 Downloading via HTTP: ${urlString.take(80)}...")

            withContext(Dispatchers.Main) {
                _exportProgress.value = "Downloading video..."
            }

            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.requestMethod = "GET"
            connection.doInput = true
            connection.instanceFollowRedirects = true
            connection.connect()

            val responseCode = connection.responseCode
            Log.d(TAG, "📥 HTTP response: $responseCode")

            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "❌ HTTP error: $responseCode")
                connection.disconnect()
                return@withContext null
            }

            val contentLength = connection.contentLength
            Log.d(TAG, "📥 Content length: ${contentLength / 1024}KB")

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        if (contentLength > 0) {
                            val progress = (totalBytesRead.toDouble() / contentLength * 100).toInt()
                            withContext(Dispatchers.Main) {
                                _exportProgress.value = "Downloading... $progress%"
                            }
                        }
                    }
                }
            }

            connection.disconnect()

            if (tempFile.exists() && tempFile.length() > 0) {
                downloadCache[cacheKey] = tempFile
                Log.d(TAG, "✅ HTTP download complete: ${tempFile.length() / 1024}KB")
                tempFile
            } else {
                Log.e(TAG, "❌ Downloaded file is empty")
                null
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Download failed: ${e.message}", e)
            null
        }
    }

    // MARK: - Link Fallback

    private fun shareLinkFallback(
        context: Context,
        video: ShareableVideo?,
        creatorUsername: String,
        threadID: String?
    ) {
        val shareText = buildString {
            append("Check out this video by @$creatorUsername on StitchSocial!")
            video?.title?.let { append("\n\n$it") }
            threadID?.let { append("\n\nstitch://thread/$it") }
            append("\n\nDownload StitchSocial: https://play.google.com/store/apps/details?id=com.stitchsocial.club")
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }

        context.startActivity(
            Intent.createChooser(shareIntent, "Share Link")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Log.d(TAG, "📤 Link-only fallback share")
    }

    // MARK: - Public Link Share

    fun shareLink(
        context: Context,
        video: ShareableVideo,
        creatorUsername: String,
        threadID: String? = null
    ) {
        shareLinkFallback(context, video, creatorUsername, threadID)
    }

    // MARK: - Helpers

    private fun createTempVideoFile(context: Context): File {
        val dir = File(context.cacheDir, "share_videos")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "StitchShare_${UUID.randomUUID()}.mp4")
    }

    // MARK: - Cleanup

    fun cleanup(context: Context? = null) {
        // Download cache
        downloadCache.values.forEach { file ->
            try { if (file.exists()) file.delete() } catch (_: Exception) {}
        }
        downloadCache.clear()

        // Share video temp dir
        val ctx = context ?: appContext
        ctx?.let {
            val dir = File(it.cacheDir, "share_videos")
            dir.listFiles()?.forEach { file ->
                try { file.delete() } catch (_: Exception) {}
            }
        }

        // Current file
        currentShareFile?.let {
            try { if (it.exists()) it.delete() } catch (_: Exception) {}
        }
        currentShareFile = null

        Log.d(TAG, "🧹 Cleaned up share temp files")
    }
}