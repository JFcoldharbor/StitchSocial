package com.stitchsocial.club.services

import android.content.Context
import com.stitchsocial.club.BuildConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped store of just-uploaded local video files, keyed by the Firestore
 * doc id. Powers optimistic playback — the poster plays their own local file instantly
 * while the CDN transcode runs, then the feed swaps to HLS once it's live (iOS parity —
 * see project_stitch_cdn_integration).
 *
 * Copies into cacheDir so the file survives the upload pipeline's temp cleanup.
 */
object LocalVideoCache {

    private val map = ConcurrentHashMap<String, String>()   // docId -> persisted local path

    private fun dir(context: Context): File =
        File(context.cacheDir, "OptimisticUploads").apply { mkdirs() }

    /** Copy [sourceFile] into the cache keyed by [videoId]. Returns the persisted path
     *  (null on copy failure — optimistic playback just degrades to MP4/HLS). */
    fun register(context: Context, videoId: String, sourceFile: File): String? {
        return try {
            val dest = File(dir(context), "$videoId.mov")
            sourceFile.copyTo(dest, overwrite = true)
            map[videoId] = dest.absolutePath
            if (BuildConfig.DEBUG) println("📥 LOCAL CACHE: registered optimistic file for $videoId")
            dest.absolutePath
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) println("⚠️ LOCAL CACHE: copy failed for $videoId — ${e.message}")
            null
        }
    }

    /** The persisted local path for [videoId], if one still exists on disk. */
    fun localPath(videoId: String): String? =
        map[videoId]?.takeIf { File(it).exists() }

    /** Drop the cached file once HLS has taken over (or on teardown). */
    fun clear(videoId: String) {
        map.remove(videoId)?.let { runCatching { File(it).delete() } }
    }
}
