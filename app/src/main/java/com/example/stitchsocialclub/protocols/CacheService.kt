package com.example.stitchsocialclub.protocols

// MARK: - Layer 2: Cache Protocols - ZERO External Dependencies
// Can import: Layer 1 (Foundation) ONLY
// NO kotlinx.coroutines, NO android.*, NO external libraries

// MARK: - Cache Service Protocol (Main Interface)

interface CacheService {
    suspend fun store(key: String, data: ByteArray, type: Any): Any
    suspend fun retrieve(key: String, type: Any): Any?
    suspend fun remove(key: String, type: Any): Any
    suspend fun clear(type: Any): Any
    suspend fun clearAll(): Any
    suspend fun getCacheSize(type: Any): Any
    suspend fun isExpired(key: String, type: Any): Boolean
    suspend fun setExpiration(key: String, type: Any, expirationTime: Long): Any
    suspend fun preloadVideo(videoId: String, videoUri: Any): Any
    suspend fun getPreloadedVideo(videoId: String): Any?
}

// MARK: - Video Preload Service Protocol

interface VideoPreloadService {
    suspend fun preloadVideo(videoId: String, videoUri: Any): Any
    suspend fun getPreloadedVideo(videoId: String): Any?
    suspend fun cancelPreload(videoId: String): Any
    suspend fun clearPreloadCache(): Any
    suspend fun getPreloadProgress(videoId: String): Any
    suspend fun setPreloadPriority(videoId: String, priority: Int): Any
}

// MARK: - Memory Cache Protocol

interface MemoryCache {
    fun put(key: String, value: Any): Any
    fun get(key: String): Any?
    fun remove(key: String): Any?
    fun clear(): Any
    fun size(): Int
    fun maxSize(): Int
    fun setMaxSize(maxSize: Int): Any
}

// MARK: - Disk Cache Protocol

interface DiskCache {
    suspend fun write(key: String, data: ByteArray): Any
    suspend fun read(key: String): Any?
    suspend fun delete(key: String): Any
    suspend fun clear(): Any
    suspend fun size(): Long
    suspend fun maxSize(): Long
    suspend fun setMaxSize(maxSize: Long): Any
    suspend fun exists(key: String): Boolean
}

// MARK: - Cache Manager Protocol

interface CacheManager {
    suspend fun initialize(): Any
    suspend fun shutdown(): Any
    suspend fun evictExpired(): Any
    suspend fun evictLeastRecentlyUsed(): Any
    suspend fun getStatistics(): Any
    suspend fun resetStatistics(): Any
}

// MARK: - Cache Observer Protocol

interface CacheObserver {
    fun onCacheHit(key: String, type: Any)
    fun onCacheMiss(key: String, type: Any)
    fun onCacheEviction(key: String, type: Any, reason: Any)
    fun onCacheError(key: String, type: Any, error: String)
}