/*
 * VideoDiskCache.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 4: Core Services — Disk-Based Video File Cache
 * Mirror of Swift VideoDiskCache.swift exactly.
 * Dependencies: ExoPlayer SimpleCache, Media3
 *
 * Solves the #1 performance issue: every video swipe re-downloads the full MP4.
 * After first play, video bytes are written to disk and served locally on repeat views.
 *
 * ARCHITECTURE:
 *   - ExoPlayer SimpleCache (LRU) backed by device disk storage
 *   - CacheDataSource.Factory wraps OkHttp → ExoPlayer reads from cache if available
 *   - prefetchVideos() background-downloads next N videos before user swipes to them
 *   - VideoPlayerComposable must use buildCacheDataSourceFactory() not DefaultDataSource
 *
 * CACHING:
 *   - Max disk: 2GB (matches Swift maxCacheSize)
 *   - Eviction: LRU (ExoPlayer SimpleCache default)
 *   - Singleton: one cache instance shared across all players
 *
 * NEVER CACHE AN ERROR AS A VIDEO (iOS parity, VideoDiskCache.swift):
 *   For about a minute after an upload, while it transcodes, CloudFront
 *   answers with an error page. Kept as the "video", that played black for
 *   the rest of the session. So a download is kept only if it is a 2xx
 *   (DefaultHttpDataSource throws on anything else, before a byte is
 *   written), its Content-Type is video/ or octet-stream (HLS playlist,
 *   audio and fMP4 types are let through too — players stream HLS through
 *   this same cache), and a prefetched progressive file is at least 50 KB. Anything
 *   else is removed from the cache, never indexed.
 *
 * ADD TO OptimizationConfig:
 *   VideoDiskCache.maxCacheSizeBytes = 2 * 1024 * 1024 * 1024L
 *   VideoDiskCache.prefetchAheadCount = 3  (prefetch next 3 on swipe)
 */

package com.stitchsocial.club.services

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import com.stitchsocial.club.BuildConfig

@OptIn(UnstableApi::class)
object VideoDiskCache {

    // ── Configuration (mirrors Swift) ────────────────────────────
    private const val MAX_CACHE_BYTES = 2L * 1024 * 1024 * 1024  // 2GB
    private const val PREFETCH_AHEAD_COUNT = 3
    private const val MAX_CONCURRENT_DOWNLOADS = 3
    private const val CACHE_DIR_NAME = "VideoFileCache"
    /** Smaller than any real clip; an error page or empty body is well under it. */
    private const val MIN_VIDEO_BYTES = 50L * 1024

    private var cache: SimpleCache? = null
    private var databaseProvider: StandaloneDatabaseProvider? = null
    private val activeDownloads = AtomicInteger(0)
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefetchingUrls = mutableSetOf<String>()
    private val prefetchLock = Any()

    // ── Initialise (call once from Application.onCreate) ─────────

    fun init(context: Context) {
        if (cache != null) return
        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
        databaseProvider = StandaloneDatabaseProvider(context)
        cache = SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
            databaseProvider!!
        )
        if (BuildConfig.DEBUG) { println("💾 VIDEO CACHE: Initialized at ${cacheDir.absolutePath}") }
    }

    // ── CacheDataSource.Factory — use this in ExoPlayer.Builder ──

    /**
     * Returns a CacheDataSource.Factory that reads from the disk cache first,
     * falls back to network, and writes new downloads to cache.
     * Pass this to ExoPlayer.Builder().setMediaSourceFactory() or use directly.
     */
    fun buildCacheDataSourceFactory(): CacheDataSource.Factory {
        val simpleCache = cache
            ?: throw IllegalStateException("VideoDiskCache.init(context) not called")

        return CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(15_000)
                    .setAllowCrossProtocolRedirects(true)
                    // A response that isn't media fails at open(), so neither
                    // the player nor a prefetch ever writes it to the cache.
                    .setContentTypePredicate { isMediaContentType(it) }
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    // ── Prefetch API (mirrors Swift prefetchVideos) ───────────────

    /**
     * Background-download up to PREFETCH_AHEAD_COUNT video URLs.
     * Skips URLs already cached or currently downloading.
     * Called by DiscoveryViewModel on swipe advance and on initial load.
     *
     * CACHING: Each URL downloaded once — SimpleCache deduplicates.
     * COST: Network bytes only on first download; 0 bytes on cache hit.
     */
    fun prefetchVideos(urls: List<String>) {
        val toFetch = urls
            .filter { it.isNotEmpty() }
            .take(PREFETCH_AHEAD_COUNT)
            .filter { url ->
                synchronized(prefetchLock) {
                    if (prefetchingUrls.contains(url)) return@filter false
                    if (isCached(url)) return@filter false
                    prefetchingUrls.add(url)
                    true
                }
            }

        if (toFetch.isEmpty()) return

        prefetchScope.launch {
            toFetch.forEach { url ->
                if (activeDownloads.get() >= MAX_CONCURRENT_DOWNLOADS) {
                    synchronized(prefetchLock) { prefetchingUrls.remove(url) }
                    return@forEach
                }
                launch {
                    activeDownloads.incrementAndGet()
                    try {
                        downloadToCache(url)
                    } finally {
                        activeDownloads.decrementAndGet()
                        synchronized(prefetchLock) { prefetchingUrls.remove(url) }
                    }
                }
            }
        }
    }

    /** Prefetch a single URL immediately (used by CollectionPlayerView on advance) */
    fun prefetchVideo(url: String) = prefetchVideos(listOf(url))

    // ── Cache check ───────────────────────────────────────────────

    fun isCached(url: String): Boolean {
        val c = cache ?: return false
        return try {
            val key = Uri.parse(url).lastPathSegment ?: url
            c.isCached(key, 0, 1)
        } catch (_: Exception) { false }
    }

    // ── Internal download ─────────────────────────────────────────

    private suspend fun downloadToCache(url: String) {
        val spec = androidx.media3.datasource.DataSpec(Uri.parse(url))
        // The key CacheDataSource writes under — needed to drop a bad entry.
        val key = CacheKeyFactory.DEFAULT.buildCacheKey(spec)
        try {
            val factory = buildCacheDataSourceFactory()
            val dataSource = factory.createDataSource()
            dataSource.open(spec)
            val buffer = ByteArray(64 * 1024)
            var bytesRead = 0L
            try {
                while (true) {
                    val read = dataSource.read(buffer, 0, buffer.size)
                    if (read == androidx.media3.common.C.RESULT_END_OF_INPUT) break
                    bytesRead += read
                }
            } finally {
                dataSource.close()
            }
            // A whole progressive file this small is an error body or a stub,
            // not a video. Playlists (.m3u8) are small by nature, so exempt.
            if (!isPlaylist(url) && bytesRead < MIN_VIDEO_BYTES) {
                removeFromCache(key)
                if (BuildConfig.DEBUG) { println("⚠️ VIDEO CACHE: Dropped ${formatBytes(bytesRead)} body for ${url.takeLast(30)} — too small to be a video") }
                return
            }
            if (BuildConfig.DEBUG) { println("💾 VIDEO CACHE: Prefetched ${formatBytes(bytesRead)} for ${url.takeLast(30)}") }
        } catch (e: Exception) {
            // Non-2xx status or a non-media Content-Type lands here. Make sure
            // nothing from the attempt stays indexed.
            removeFromCache(key)
            if (BuildConfig.DEBUG) { println("⚠️ VIDEO CACHE: Prefetch failed for ${url.takeLast(30)}: ${e.message}") }
        }
    }

    private fun removeFromCache(key: String) {
        try { cache?.removeResource(key) } catch (_: Exception) {}
    }

    // ── Response validation ───────────────────────────────────────

    /**
     * video/…, or the octet-stream types S3 serves untyped uploads as. HLS
     * playlist types, audio/… and application/mp4 pass too because players
     * stream HLS (playlists, audio renditions, fMP4 segments) through this
     * cache. Missing, text/… (an HTML error page), application/xml
     * (S3/CloudFront's AccessDenied body) and JSON all fail.
     */
    private fun isMediaContentType(raw: String?): Boolean {
        val type = raw?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return type.startsWith("video/") ||
            type.startsWith("audio/") ||
            type == "application/mp4" ||
            type == "application/octet-stream" ||
            type == "binary/octet-stream" ||
            type == "application/vnd.apple.mpegurl" ||
            type == "application/x-mpegurl"
    }

    private fun isPlaylist(url: String): Boolean =
        Uri.parse(url).lastPathSegment?.endsWith(".m3u8", ignoreCase = true) == true

    // ── Cleanup (call from Application.onTerminate or low-memory) ─

    fun release() {
        prefetchScope.cancel()
        cache?.release()
        cache = null
    }

    fun clearAll() {
        try {
            // SimpleCache: iterate keys and remove each resource
            cache?.let { c ->
                c.keys.toList().forEach { key ->
                    try { c.removeResource(key) } catch (_: Exception) {}
                }
            }
            if (BuildConfig.DEBUG) { println("🗑️ VIDEO CACHE: Cleared all cached videos") }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { println("⚠️ VIDEO CACHE: Clear failed: ${e.message}") }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }
}