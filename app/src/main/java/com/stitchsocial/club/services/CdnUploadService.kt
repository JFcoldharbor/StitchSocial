package com.stitchsocial.club.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Talks to Stephen's HLS/ABR + CDN pipeline (iOS parity — see project_stitch_cdn_integration).
 *
 * Flow:
 *   1. POST /upload-url  -> presigned S3 PUT + public CloudFront HLS/MP4 URLs
 *   2. PUT raw source to S3 (streams from disk)
 * Playback URLs are PUBLIC CloudFront — never signed/expiring, so no client-side
 * 403/refresh logic is needed. Only the UPLOAD url is presigned (1hr).
 */
object CdnUploadService {

    private const val UPLOAD_URL = "https://b0i5u0bheb.execute-api.us-east-1.amazonaws.com/prod/upload-url"

    /** Backend auth is a PLACEHOLDER for now (no real validation) per Stephen. */
    private const val AUTH_TOKEN = "Bearer any-token-for-now"

    /**
     * The EXACT Content-Type the presign signs (content-type;host) against the
     * `source.mov` key. Verified: `video/mp4`, `application/octet-stream`, and empty
     * all return 403 SignatureDoesNotMatch. Do NOT change without re-checking the
     * Lambda — S3 byte content is not validated against this.
     */
    private const val SOURCE_CONTENT_TYPE = "video/quicktime"

    private val client = OkHttpClient()

    /** Result of step 1: a presigned upload target + the public CDN playback URLs. */
    data class Ticket(
        val videoId: String,
        val uploadURL: String,
        val hlsURL: String,   // public CloudFront ABR master (.m3u8)
        val mp4URL: String    // public CloudFront faststart MP4 fallback
    )

    /** Step 1: request presigned upload + CDN playback URLs for a videoId. */
    suspend fun requestTicket(videoId: String): Ticket = withContext(Dispatchers.IO) {
        val body = JSONObject().put("videoId", videoId).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(UPLOAD_URL)
            .header("Authorization", AUTH_TOKEN)
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("CDN upload-url failed: HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: throw IOException("empty upload-url body"))
            Ticket(
                videoId = json.optString("videoId", videoId),
                uploadURL = json.getString("uploadURL"),
                hlsURL = json.getString("hlsURL"),
                mp4URL = json.getString("mp4URL")
            )
        }
    }

    /**
     * Step 2: PUT the raw source file to the presigned S3 URL. Streams from disk.
     * Content-Type MUST be `video/quicktime` to match the presign signature.
     */
    suspend fun uploadSource(file: File, uploadURL: String) = withContext(Dispatchers.IO) {
        assertPlayable(file)
        val req = Request.Builder()
            .url(uploadURL)
            .put(file.asRequestBody(SOURCE_CONTENT_TYPE.toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("CDN source upload failed: HTTP ${resp.code}")
        }
    }

    // ── Guard: never upload an empty video ──────────────────────────────
    // A video export can fail and still leave a file: a container header and
    // one black frame (~1KB, 0.07s). Nine of those were uploaded and posted —
    // black in every feed, nothing when shared. Every source upload passes
    // through here, so this is where they stop.
    private const val MIN_BYTES = 50L * 1024
    private const val MIN_MS = 500L

    fun assertPlayable(file: File) {
        if (!file.exists() || file.length() < MIN_BYTES) throw EmptyVideoException()
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val ms = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (!hasVideo || ms < MIN_MS) throw EmptyVideoException()
        } catch (e: EmptyVideoException) {
            throw e
        } catch (e: Exception) {
            // Unreadable as a video at all.
            throw EmptyVideoException()
        } finally {
            runCatching { retriever.release() }
        }
    }
}

class EmptyVideoException : IOException(
    "This video came out empty, so it wasn't posted. Pick or record it again — if it's from your gallery, make sure it's fully downloaded to your phone first."
)
