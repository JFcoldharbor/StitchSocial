package com.stitchsocial.club.live

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Per-stream disk cache for video comments, mirroring iOS `StreamClipCache`.
 * Both platforms key off the URL's PATH portion (token-stable) so the same
 * Storage object always maps to the same cache file regardless of which
 * signed download URL was used to request it.
 *
 * Storage location: `context.cacheDir/stitch-stream-clips/<keyed>.mp4`
 * — Android may evict on disk pressure, same as iOS tmp behavior.
 *
 * Lifecycle:
 *  - Call [init] once at app launch (provides Context).
 *  - [prefetch] from snapshot listeners.
 *  - [cachedURL] from the player.
 *  - [purge] on stream end.
 */
object StreamClipCache {

    private const val TAG = "StreamClipCache"
    private const val DIR_NAME = "stitch-stream-clips"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = mutableMapOf<String, Deferred<String?>>()
    @Volatile private var cacheDir: File? = null

    fun init(context: Context) {
        if (cacheDir != null) return
        val dir = File(context.cacheDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        cacheDir = dir
    }

    /**
     * Returns a `file://` URI for the cached clip if on disk, or `null`.
     * Callers fall back to the remote URL on miss. We return the `file://`
     * scheme (not a bare absolute path) so ExoPlayer's `MediaItem.fromUri`
     * never misclassifies it as a relative URI.
     */
    fun cachedURL(remoteURL: String): String? {
        val dir = cacheDir ?: return null
        val key = cacheKey(remoteURL) ?: return null
        val local = File(dir, "$key.mp4")
        return if (local.exists()) android.net.Uri.fromFile(local).toString() else null
    }

    /**
     * Background download. Safe to call multiple times for the same URL —
     * the in-flight map dedupes concurrent fetches.
     */
    fun prefetch(remoteURL: String) {
        val dir = cacheDir ?: return
        val key = cacheKey(remoteURL) ?: return
        val local = File(dir, "$key.mp4")
        if (local.exists()) return

        synchronized(inFlight) {
            if (inFlight.containsKey(key)) return

            val task: Deferred<String?> = scope.async {
                try {
                    val conn = URL(remoteURL).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    conn.requestMethod = "GET"
                    conn.connect()
                    if (conn.responseCode !in 200..299) {
                        Log.w(TAG, "⚠️ download HTTP ${conn.responseCode} for ${remoteURL.takeLast(40)}")
                        return@async null
                    }
                    conn.inputStream.use { input ->
                        local.outputStream().use { output -> input.copyTo(output) }
                    }
                    Log.d(TAG, "📦 cached ${local.name}")
                    local.absolutePath
                } catch (t: Throwable) {
                    Log.w(TAG, "⚠️ download failed: ${t.localizedMessage}")
                    null
                } finally {
                    synchronized(inFlight) { inFlight.remove(key) }
                }
            }
            inFlight[key] = task
        }
    }

    /**
     * Wipes the cache directory. Called on stream end so ephemeral clips
     * don't accumulate on disk across sessions.
     */
    fun purge() {
        synchronized(inFlight) {
            inFlight.values.forEach { it.cancel() }
            inFlight.clear()
        }
        val dir = cacheDir ?: return
        dir.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "🗑️ purged")
    }

    /**
     * Key derivation: URL-decode the path, then sanitize to ONLY filesystem-
     * safe characters (alphanumeric, dot, underscore, dash). Anything else
     * (including `%`, which lingered from raw `%2F` URL-encoding) becomes
     * `_`. This is critical because ExoPlayer parses local file paths as
     * URIs — a filename with literal `%` would get URL-decoded to a
     * different path that doesn't exist on disk.
     *
     * Firebase Storage rotates `?token=…` per request but the path part
     * stays stable, so this key is token-agnostic.
     */
    private fun cacheKey(remoteURL: String): String? {
        return try {
            val uri = java.net.URI(remoteURL)
            val rawPath = uri.rawPath ?: uri.path ?: return null
            val decoded = java.net.URLDecoder.decode(rawPath, "UTF-8")
            decoded
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .trim('_')
                .take(120)
        } catch (_: Throwable) {
            null
        }
    }
}
