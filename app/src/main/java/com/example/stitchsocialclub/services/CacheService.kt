package com.example.stitchsocialclub.services

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.*
import kotlin.collections.LinkedHashMap

class CacheService(private val context: Context) {

    enum class CacheType {
        VIDEO,
        IMAGE,
        DATA,
        THUMBNAIL,
        UPLOAD_RESULT,
        USER_PROFILE,
        THREAD_DATA,
        ENGAGEMENT;

        val prefix: String
            get() = when (this) {
                VIDEO -> "vid"
                IMAGE -> "img"
                DATA -> "dat"
                THUMBNAIL -> "tmb"
                UPLOAD_RESULT -> "upl"
                USER_PROFILE -> "usr"
                THREAD_DATA -> "thd"
                ENGAGEMENT -> "eng"
            }
    }

    data class CacheEntry<T>(
        val key: String,
        val value: T,
        val timestamp: Long,
        val accessCount: Int,
        val lastAccessed: Long,
        val expiresAt: Long?
    ) {
        val isExpired: Boolean
            get() = expiresAt != null && System.currentTimeMillis() > expiresAt

        val age: Long
            get() = System.currentTimeMillis() - timestamp

        fun accessed(): CacheEntry<T> {
            return copy(
                accessCount = accessCount + 1,
                lastAccessed = System.currentTimeMillis()
            )
        }
    }

    data class CacheMetrics(
        val memoryHits: Long = 0,
        val memoryMisses: Long = 0,
        val persistentHits: Long = 0,
        val persistentMisses: Long = 0,
        val evictions: Long = 0,
        val errors: Long = 0
    ) {
        val totalHits: Long get() = memoryHits + persistentHits
        val totalMisses: Long get() = memoryMisses + persistentMisses
        val totalRequests: Long get() = totalHits + totalMisses

        val hitRate: Double
            get() = if (totalRequests > 0) totalHits.toDouble() / totalRequests else 0.0

        val memoryHitRate: Double
            get() = if (totalRequests > 0) memoryHits.toDouble() / totalRequests else 0.0
    }

    data class CacheConfig(
        val maxMemorySize: Int = 50,
        val maxPersistentSize: Int = 500,
        val defaultTtlMs: Long = 3600000,
        val cleanupIntervalMs: Long = 300000,
        val enablePersistence: Boolean = true
    )

    sealed class CacheError(override val message: String) : Exception(message) {
        class SerializationError(message: String) : CacheError("Serialization Error: $message")
        class StorageError(message: String) : CacheError("Storage Error: $message")
        class InvalidKeyError(message: String) : CacheError("Invalid Key: $message")
        object CacheFullError : CacheError("Cache is full and cannot accept new entries")
    }

    private val config = CacheConfig()
    private val mutex = Mutex()

    private val memoryCache = object : LinkedHashMap<String, CacheEntry<Any>>(
        config.maxMemorySize + 1,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry<Any>>?): Boolean {
            val shouldRemove = size > config.maxMemorySize
            if (shouldRemove) {
                _metrics.value = _metrics.value.copy(evictions = _metrics.value.evictions + 1)
            }
            return shouldRemove
        }
    }

    private val sharedPrefs: SharedPreferences = context.getSharedPreferences(
        "stitch_cache",
        Context.MODE_PRIVATE
    )

    private val cacheDir: File = File(context.cacheDir, "stitch_cache").apply {
        if (!exists()) mkdirs()
    }

    private val _metrics = MutableStateFlow(CacheMetrics())
    val metrics: StateFlow<CacheMetrics> = _metrics.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var lastCleanup = System.currentTimeMillis()

    suspend fun <T> put(
        key: String,
        value: T,
        type: CacheType,
        ttlMs: Long? = null
    ) {
        if (!isValidKey(key)) {
            throw CacheError.InvalidKeyError("Key must not be empty and should contain only valid characters")
        }

        mutex.withLock {
            try {
                val prefixedKey = "${type.prefix}_$key"
                val now = System.currentTimeMillis()
                val expiresAt = ttlMs?.let { now + it } ?: (now + config.defaultTtlMs)

                val entry = CacheEntry(
                    key = prefixedKey,
                    value = value as Any,
                    timestamp = now,
                    accessCount = 0,
                    lastAccessed = now,
                    expiresAt = expiresAt
                )

                memoryCache[prefixedKey] = entry

                if (config.enablePersistence) {
                    storePersistent(prefixedKey, value, expiresAt)
                }

                cleanupIfNeeded()

            } catch (e: Exception) {
                _metrics.value = _metrics.value.copy(errors = _metrics.value.errors + 1)
                throw CacheError.StorageError("Failed to store cache entry: ${e.message}")
            }
        }
    }

    suspend fun <T> get(key: String, type: CacheType): T? {
        if (!isValidKey(key)) {
            return null
        }

        return mutex.withLock {
            val prefixedKey = "${type.prefix}_$key"

            memoryCache[prefixedKey]?.let { entry ->
                if (!entry.isExpired) {
                    memoryCache[prefixedKey] = entry.accessed()
                    _metrics.value = _metrics.value.copy(memoryHits = _metrics.value.memoryHits + 1)
                    @Suppress("UNCHECKED_CAST")
                    return@withLock entry.value as T
                } else {
                    memoryCache.remove(prefixedKey)
                }
            }

            if (config.enablePersistence) {
                val persistentValue = loadPersistent<T>(prefixedKey)
                if (persistentValue != null) {
                    val now = System.currentTimeMillis()
                    val entry = CacheEntry(
                        key = prefixedKey,
                        value = persistentValue as Any,
                        timestamp = now,
                        accessCount = 1,
                        lastAccessed = now,
                        expiresAt = now + config.defaultTtlMs
                    )
                    memoryCache[prefixedKey] = entry

                    _metrics.value = _metrics.value.copy(persistentHits = _metrics.value.persistentHits + 1)
                    return@withLock persistentValue
                }
            }

            _metrics.value = _metrics.value.copy(
                memoryMisses = _metrics.value.memoryMisses + 1,
                persistentMisses = _metrics.value.persistentMisses + 1
            )
            null
        }
    }

    suspend fun remove(key: String, type: CacheType) {
        mutex.withLock {
            val prefixedKey = "${type.prefix}_$key"

            memoryCache.remove(prefixedKey)

            if (config.enablePersistence) {
                removePersistent(prefixedKey)
            }
        }
    }

    suspend fun clearType(type: CacheType) {
        mutex.withLock {
            val prefix = "${type.prefix}_"

            val keysToRemove = memoryCache.keys.filter { it.startsWith(prefix) }
            keysToRemove.forEach { memoryCache.remove(it) }

            if (config.enablePersistence) {
                clearPersistentType(type)
            }
        }
    }

    suspend fun clearAll() {
        mutex.withLock {
            memoryCache.clear()

            if (config.enablePersistence) {
                sharedPrefs.edit().clear().apply()
                cacheDir.listFiles()?.forEach { it.delete() }
            }

            _metrics.value = CacheMetrics()
        }
    }

    suspend fun contains(key: String, type: CacheType): Boolean {
        return get<Any>(key, type) != null
    }

    suspend fun getCacheSize(): Pair<Int, Int> {
        return mutex.withLock {
            val memorySize = memoryCache.size
            val persistentSize = if (config.enablePersistence) {
                sharedPrefs.all.size
            } else {
                0
            }
            Pair(memorySize, persistentSize)
        }
    }

    suspend fun cleanup() {
        mutex.withLock {
            val now = System.currentTimeMillis()

            val expiredKeys = memoryCache.entries
                .filter { it.value.isExpired }
                .map { it.key }

            expiredKeys.forEach {
                memoryCache.remove(it)
                _metrics.value = _metrics.value.copy(evictions = _metrics.value.evictions + 1)
            }

            if (config.enablePersistence) {
                cleanupPersistent()
            }

            lastCleanup = now
            println("CACHE SERVICE: Cleanup completed - removed ${expiredKeys.size} expired entries")
        }
    }

    private fun isValidKey(key: String): Boolean {
        return key.isNotBlank() && key.length <= 250 && !key.contains("/")
    }

    private fun cleanupIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastCleanup > config.cleanupIntervalMs) {
            lastCleanup = now
        }
    }

    private fun storePersistent(key: String, value: Any, expiresAt: Long) {
        try {
            when (value) {
                is String -> {
                    sharedPrefs.edit()
                        .putString("${key}_value", value)
                        .putLong("${key}_expires", expiresAt)
                        .apply()
                }
                is Int -> {
                    sharedPrefs.edit()
                        .putInt("${key}_value", value)
                        .putLong("${key}_expires", expiresAt)
                        .apply()
                }
                is Long -> {
                    sharedPrefs.edit()
                        .putLong("${key}_value", value)
                        .putLong("${key}_expires", expiresAt)
                        .apply()
                }
                is Boolean -> {
                    sharedPrefs.edit()
                        .putBoolean("${key}_value", value)
                        .putLong("${key}_expires", expiresAt)
                        .apply()
                }
                is Float -> {
                    sharedPrefs.edit()
                        .putFloat("${key}_value", value)
                        .putLong("${key}_expires", expiresAt)
                        .apply()
                }
                else -> {
                    sharedPrefs.edit()
                        .putString("${key}_value", value.toString())
                        .putLong("${key}_expires", expiresAt)
                        .apply()
                }
            }
        } catch (e: Exception) {
            println("CACHE SERVICE: Failed to store persistent data: $e")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> loadPersistent(key: String): T? {
        try {
            val expiresAt = sharedPrefs.getLong("${key}_expires", 0)
            if (expiresAt > 0 && System.currentTimeMillis() > expiresAt) {
                removePersistent(key)
                return null
            }

            return when {
                sharedPrefs.contains("${key}_value") -> {
                    val stringValue = sharedPrefs.getString("${key}_value", null)
                    stringValue as? T
                }
                else -> null
            }
        } catch (e: Exception) {
            println("CACHE SERVICE: Failed to load persistent data: $e")
            return null
        }
    }

    private fun removePersistent(key: String) {
        try {
            sharedPrefs.edit()
                .remove("${key}_value")
                .remove("${key}_expires")
                .apply()
        } catch (e: Exception) {
            println("CACHE SERVICE: Failed to remove persistent data: $e")
        }
    }

    private fun clearPersistentType(type: CacheType) {
        try {
            val prefix = "${type.prefix}_"
            val editor = sharedPrefs.edit()

            sharedPrefs.all.keys
                .filter { it.startsWith(prefix) }
                .forEach { editor.remove(it) }

            editor.apply()
        } catch (e: Exception) {
            println("CACHE SERVICE: Failed to clear persistent type: $e")
        }
    }

    private fun cleanupPersistent() {
        try {
            val now = System.currentTimeMillis()
            val editor = sharedPrefs.edit()

            sharedPrefs.all.entries
                .filter { it.key.endsWith("_expires") }
                .forEach { entry ->
                    val expiresAt = entry.value as? Long ?: 0
                    if (expiresAt > 0 && now > expiresAt) {
                        val baseKey = entry.key.removeSuffix("_expires")
                        editor.remove("${baseKey}_value")
                        editor.remove("${baseKey}_expires")
                    }
                }

            editor.apply()
        } catch (e: Exception) {
            println("CACHE SERVICE: Failed to cleanup persistent storage: $e")
        }
    }

    fun helloWorldTest() {
        println("CACHE SERVICE: Hello World - Two-tier caching ready!")
        println("CACHE SERVICE: Features: Memory + Persistent storage, TTL, Metrics")
        println("CACHE SERVICE: Performance: Thread-safe operations with LRU eviction")
    }

    suspend fun getCacheStatus(): String {
        val (memorySize, persistentSize) = getCacheSize()
        val currentMetrics = _metrics.value

        return """
        CACHE STATUS:
        - Memory entries: $memorySize/${config.maxMemorySize}
        - Persistent entries: $persistentSize/${config.maxPersistentSize}
        - Hit rate: ${String.format("%.1f%%", currentMetrics.hitRate * 100)}
        - Memory hit rate: ${String.format("%.1f%%", currentMetrics.memoryHitRate * 100)}
        - Total requests: ${currentMetrics.totalRequests}
        - Evictions: ${currentMetrics.evictions}
        - Errors: ${currentMetrics.errors}
        """.trimIndent()
    }
}