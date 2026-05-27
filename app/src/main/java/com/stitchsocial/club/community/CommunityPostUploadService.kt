package com.stitchsocial.club.community

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
 * Uploads a video clip + JPEG thumbnail to the Storage path used by community
 * video-thread posts: `community-posts/{communityID}/{postID}.mp4` and
 * `community-posts/{communityID}/{postID}_thumb.jpg`.
 *
 * Mirrors the existing [com.stitchsocial.club.live.StreamClipUploadService]
 * shape — same first-frame thumbnail extraction, same progress callback,
 * same async upload pattern. Storage rules need a matching read+write block
 * for the community-posts path.
 */
object CommunityPostUploadService {

    private const val TAG = "PostUpload"
    private const val THUMB_MAX_DIMENSION = 720

    data class UploadResult(
        val videoURL: String,
        val thumbnailURL: String?,
        val durationSeconds: Int,
    )

    private val storage: FirebaseStorage by lazy { FirebaseStorage.getInstance() }

    suspend fun uploadPostVideo(
        context: Context,
        localUri: Uri,
        communityID: String,
        postID: String,
        onProgress: (Float) -> Unit = {},
    ): UploadResult = withContext(Dispatchers.IO) {
        val durationMs = readDurationMillis(context, localUri)
        val durationSec = (durationMs / 1000).toInt().coerceAtLeast(1)

        onProgress(0.1f)

        // Thumbnail first — it's tiny, gets us a fast UI signal
        val thumbBytes = generateThumbnailBytes(context, localUri)
        val thumbRef = storage.reference
            .child("community-posts/$communityID/${postID}_thumb.jpg")
        val thumbnailURL = if (thumbBytes != null) {
            runCatching {
                thumbRef.putBytes(
                    thumbBytes,
                    StorageMetadata.Builder().setContentType("image/jpeg").build()
                ).await()
                thumbRef.downloadUrl.await().toString()
            }.getOrNull()
        } else null
        onProgress(0.3f)

        val videoRef = storage.reference
            .child("community-posts/$communityID/$postID.mp4")

        Log.d(TAG, "📦 uploading post $postID via putFile (streaming)")

        // putFile streams from the content URI — Storage SDK reads in chunks
        // instead of slurping the whole video into RAM (was OOM-killing on
        // 200MB+ uploads). Mirrors the iOS putData → putFile change.
        val task = videoRef.putFile(
            localUri,
            StorageMetadata.Builder().setContentType("video/mp4").build()
        )
        task.addOnProgressListener { snap ->
            val fraction = snap.bytesTransferred.toFloat() / snap.totalByteCount.toFloat()
            onProgress(0.3f + fraction * 0.7f)
        }
        task.await()

        val videoURL = videoRef.downloadUrl.await().toString()
        onProgress(1.0f)
        Log.d(TAG, "✅ uploaded post video $postID — ${durationSec}s")

        UploadResult(
            videoURL = videoURL,
            thumbnailURL = thumbnailURL,
            durationSeconds = durationSec,
        )
    }

    // ── Helpers (mirrors StreamClipUploadService) ───────────────────────────

    private fun readDurationMillis(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        }.getOrDefault(0L).also { runCatching { retriever.release() } }
    }

    private fun generateThumbnailBytes(context: Context, uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val frame: Bitmap = retriever.getFrameAtTime(0) ?: return null
            val scaled = scaleBitmap(frame, THUMB_MAX_DIMENSION)
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            scaled.recycle()
            if (scaled !== frame) frame.recycle()
            baos.toByteArray()
        } catch (t: Throwable) {
            Log.w(TAG, "thumbnail gen failed: ${t.localizedMessage}")
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
