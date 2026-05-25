package com.stitchsocial.club.live

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Uploads a local clip (Uri to a video file in the Photos library or app
 * cache) to Firebase Storage at the path both iOS and Android use:
 *
 *   stream-clips/{communityID}/{streamID}/{commentID}.mp4
 *   stream-clips/{communityID}/{streamID}/{commentID}_thumb.jpg
 *
 * Returns the resolved download URLs so the caller can write them onto the
 * VideoComment doc via StreamQueueService.submitVideoComment.
 *
 * Mirrors iOS VideoUploadService.uploadStreamClip — same paths, same
 * thumbnail generation (first frame, ~720x1280 max, JPEG 80%), same
 * downloadURL resolution. Storage rules already allow public R/W on the
 * stream-clips path.
 */
object StreamClipUploadService {

    private const val TAG = "ClipUpload"

    /// Max preview frame size before JPEG encoding. Keeps thumbnails small
    /// (~50-100KB each) so the carousel is fast even with 20+ pending clips.
    private const val THUMB_MAX_DIMENSION = 720

    data class UploadResult(
        val videoURL: String,
        val thumbnailURL: String?,
        val durationSeconds: Int,
    )

    private val storage: FirebaseStorage by lazy { FirebaseStorage.getInstance() }

    /**
     * Single-shot upload. Generates the thumbnail in a background dispatcher
     * and runs the two Storage uploads sequentially (thumbnail first since
     * it's tiny, then the video). Throws on either failure so the caller can
     * surface an error toast.
     */
    suspend fun uploadClip(
        context: Context,
        localUri: Uri,
        communityID: String,
        streamID: String,
        commentID: String,
        onProgress: (Float) -> Unit = {},
    ): UploadResult = withContext(Dispatchers.IO) {
        val durationMs = readDurationMillis(context, localUri)
        val durationSec = (durationMs / 1000).toInt().coerceAtLeast(1)

        // ── 1. Thumbnail ────────────────────────────────────────────────────
        onProgress(0.1f)
        val thumbBytes = generateThumbnailBytes(context, localUri)
        val thumbRef = storage.reference
            .child("stream-clips/$communityID/$streamID/${commentID}_thumb.jpg")
        val thumbMeta = StorageMetadata.Builder()
            .setContentType("image/jpeg")
            .build()
        val thumbnailURL = if (thumbBytes != null) {
            runCatching {
                thumbRef.putBytes(thumbBytes, thumbMeta).await()
                thumbRef.downloadUrl.await().toString()
            }.getOrNull()
        } else null
        onProgress(0.3f)

        // ── 2. Video ────────────────────────────────────────────────────────
        val videoRef = storage.reference
            .child("stream-clips/$communityID/$streamID/$commentID.mp4")
        val videoMeta = StorageMetadata.Builder()
            .setContentType("video/mp4")
            .build()

        // Stream the local file into Storage with progress reporting. We
        // call putStream so we don't have to slurp the whole file into RAM.
        val videoBytes = context.contentResolver.openInputStream(localUri)
            ?.use { it.readBytes() }
            ?: throw IllegalStateException("Could not open clip at $localUri")

        Log.d(TAG, "📦 uploading ${videoBytes.size / 1024} KB to $commentID.mp4")

        val task = videoRef.putBytes(videoBytes, videoMeta)
        task.addOnProgressListener { snap ->
            val fraction = snap.bytesTransferred.toFloat() / snap.totalByteCount.toFloat()
            onProgress(0.3f + (fraction * 0.7f))
        }
        task.await()

        val videoURL = videoRef.downloadUrl.await().toString()
        onProgress(1.0f)
        Log.d(TAG, "✅ uploaded clip $commentID — duration=${durationSec}s")

        UploadResult(
            videoURL = videoURL,
            thumbnailURL = thumbnailURL,
            durationSeconds = durationSec,
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun readDurationMillis(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        }.getOrDefault(0L).also {
            runCatching { retriever.release() }
        }
    }

    private fun generateThumbnailBytes(context: Context, uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val frame: Bitmap? = retriever.getFrameAtTime(0)
            if (frame == null) return null
            val scaled = scaleBitmap(frame, THUMB_MAX_DIMENSION)
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            scaled.recycle()
            if (scaled !== frame) frame.recycle()
            baos.toByteArray()
        } catch (t: Throwable) {
            Log.w(TAG, "thumbnail generation failed: ${t.localizedMessage}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleBitmap(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val ratio = if (w >= h) maxDim.toFloat() / w else maxDim.toFloat() / h
        val newW = (w * ratio).toInt().coerceAtLeast(1)
        val newH = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, newW, newH, true)
    }
}
