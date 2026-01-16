/*
 * LocalDraftManager.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 5: Business Logic - Local Draft Management
 * Dependencies: VideoEditState
 * Features: Save/load drafts locally, auto-save, cleanup
 *
 * Uses Android's built-in JSONObject (no external dependencies)
 */

package com.stitchsocial.club

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Manages local video drafts before they're posted
 */
class LocalDraftManager private constructor(private val context: Context) {

    // MARK: - Singleton

    companion object {
        @Volatile
        private var instance: LocalDraftManager? = null

        fun getInstance(context: Context): LocalDraftManager {
            return instance ?: synchronized(this) {
                instance ?: LocalDraftManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // MARK: - State

    private val _drafts = MutableStateFlow<List<VideoEditState>>(emptyList())
    val drafts: StateFlow<List<VideoEditState>> = _drafts

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // MARK: - Configuration

    private val maxDrafts = 10
    private val maxDraftAgeMs = TimeUnit.DAYS.toMillis(7) // 7 days

    // Coroutine scope for auto-save
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // MARK: - Directories

    private val draftsDirectory: File by lazy {
        File(context.filesDir, "VideoEditDrafts").also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    // MARK: - Initialization

    init {
        println("📁 DRAFT MANAGER: Initialized at ${draftsDirectory.absolutePath}")
    }

    // MARK: - Public Interface

    /**
     * Save draft to disk
     */
    suspend fun saveDraft(editState: VideoEditState) = withContext(Dispatchers.IO) {
        try {
            val draftFile = draftFile(editState.draftId)

            // Serialize to JSON
            val json = editState.toJson()
            draftFile.writeText(json.toString(2))

            // Update in-memory list
            val currentDrafts = _drafts.value.toMutableList()
            val existingIndex = currentDrafts.indexOfFirst { it.draftId == editState.draftId }

            if (existingIndex >= 0) {
                currentDrafts[existingIndex] = editState
            } else {
                currentDrafts.add(editState)

                // Enforce max drafts limit
                if (currentDrafts.size > maxDrafts) {
                    val oldestDraft = currentDrafts.minByOrNull { it.lastModified }
                    oldestDraft?.let { deleteDraft(it.draftId) }
                }
            }

            _drafts.value = currentDrafts.sortedByDescending { it.lastModified }

            println("💾 DRAFT MANAGER: Saved draft ${editState.draftId}")

        } catch (e: Exception) {
            println("❌ DRAFT MANAGER: Failed to save draft: ${e.message}")
            throw e
        }
    }

    /**
     * Auto-save draft (with debounce)
     */
    fun autoSaveDraft(editState: VideoEditState) {
        scope.launch {
            delay(500) // 0.5s debounce
            try {
                saveDraft(editState)
            } catch (e: Exception) {
                println("⚠️ DRAFT MANAGER: Auto-save failed: ${e.message}")
            }
        }
    }

    /**
     * Load specific draft
     */
    suspend fun loadDraft(id: String): VideoEditState? = withContext(Dispatchers.IO) {
        try {
            val draftFile = draftFile(id)

            if (!draftFile.exists()) {
                return@withContext null
            }

            val json = JSONObject(draftFile.readText())
            VideoEditState.fromJson(json)

        } catch (e: Exception) {
            println("⚠️ DRAFT MANAGER: Failed to load draft $id: ${e.message}")
            null
        }
    }

    /**
     * Delete draft
     */
    suspend fun deleteDraft(id: String) = withContext(Dispatchers.IO) {
        try {
            val draftFile = draftFile(id)
            draftFile.delete()

            // Remove from memory
            _drafts.value = _drafts.value.filter { it.draftId != id }

            println("🗑️ DRAFT MANAGER: Deleted draft $id")

        } catch (e: Exception) {
            println("⚠️ DRAFT MANAGER: Failed to delete draft $id: ${e.message}")
        }
    }

    /**
     * Load all drafts from disk
     */
    suspend fun loadDrafts() = withContext(Dispatchers.IO) {
        _isLoading.value = true

        try {
            val files = draftsDirectory.listFiles { file ->
                file.extension == "draft"
            } ?: emptyArray()

            val loadedDrafts = mutableListOf<VideoEditState>()
            val now = Date()

            for (file in files) {
                try {
                    val json = JSONObject(file.readText())
                    val draft = VideoEditState.fromJson(json)

                    // Check if draft is too old
                    val ageMs = now.time - draft.lastModified.time
                    if (ageMs < maxDraftAgeMs) {
                        loadedDrafts.add(draft)
                    } else {
                        // Delete old draft
                        file.delete()
                        println("🗑️ DRAFT MANAGER: Deleted expired draft ${draft.draftId}")
                    }

                } catch (e: Exception) {
                    println("⚠️ DRAFT MANAGER: Failed to load draft from ${file.name}: ${e.message}")
                }
            }

            // Sort by last modified (newest first)
            loadedDrafts.sortByDescending { it.lastModified }

            _drafts.value = loadedDrafts

            println("📂 DRAFT MANAGER: Loaded ${loadedDrafts.size} drafts")

        } catch (e: Exception) {
            println("❌ DRAFT MANAGER: Failed to load drafts: ${e.message}")
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Clean up old drafts and orphaned video files
     */
    suspend fun cleanupOldDrafts() = withContext(Dispatchers.IO) {
        var deletedCount = 0
        val now = Date()

        for (draft in _drafts.value) {
            val ageMs = now.time - draft.lastModified.time
            if (ageMs > maxDraftAgeMs) {
                try {
                    deleteDraft(draft.draftId)
                    deletedCount++
                } catch (e: Exception) {
                    // Continue with other drafts
                }
            }
        }

        if (deletedCount > 0) {
            println("🧹 DRAFT MANAGER: Cleaned up $deletedCount old drafts")
        }
    }

    /**
     * Get draft by ID from memory
     */
    fun getDraft(id: String): VideoEditState? {
        return _drafts.value.find { it.draftId == id }
    }

    /**
     * Check if draft exists
     */
    fun hasDraft(id: String): Boolean {
        return draftFile(id).exists()
    }

    // MARK: - Private Helpers

    private fun draftFile(draftId: String): File {
        return File(draftsDirectory, "$draftId.draft")
    }
}

// MARK: - Draft List Item (for UI)

data class DraftListItem(
    val id: String,
    val thumbnailUri: Uri?,
    val duration: Double,
    val lastModified: Date,
    val isProcessing: Boolean
) {
    val formattedDuration: String
        get() {
            val minutes = (duration / 60).toInt()
            val seconds = (duration % 60).toInt()
            return String.format("%d:%02d", minutes, seconds)
        }

    val formattedDate: String
        get() {
            val now = System.currentTimeMillis()
            val diff = now - lastModified.time

            return when {
                diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
                diff < TimeUnit.HOURS.toMillis(1) -> "${diff / TimeUnit.MINUTES.toMillis(1)}m ago"
                diff < TimeUnit.DAYS.toMillis(1) -> "${diff / TimeUnit.HOURS.toMillis(1)}h ago"
                diff < TimeUnit.DAYS.toMillis(7) -> "${diff / TimeUnit.DAYS.toMillis(1)}d ago"
                else -> {
                    val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
                    sdf.format(lastModified)
                }
            }
        }

    companion object {
        fun from(editState: VideoEditState): DraftListItem {
            return DraftListItem(
                id = editState.draftId,
                thumbnailUri = editState.processedThumbnailUri,
                duration = editState.trimmedDuration,
                lastModified = editState.lastModified,
                isProcessing = editState.isProcessing
            )
        }
    }
}

// MARK: - JSON Serialization Extensions for VideoEditState

fun VideoEditState.toJson(): JSONObject {
    return JSONObject().apply {
        put("draftId", draftId)
        put("videoUri", videoUri.toString())
        put("videoDuration", videoDuration)
        put("videoWidth", videoWidth)
        put("videoHeight", videoHeight)
        put("trimStartTime", trimStartTime)
        put("trimEndTime", trimEndTime)
        put("selectedFilter", selectedFilter?.name)
        put("filterIntensity", filterIntensity)
        put("isProcessing", isProcessing)
        put("processingProgress", processingProgress)
        put("isProcessingComplete", isProcessingComplete)
        put("processedVideoUri", processedVideoUri?.toString())
        put("processedThumbnailUri", processedThumbnailUri?.toString())
        put("lastModified", lastModified.time)

        // Serialize captions
        val captionsArray = JSONArray()
        for (caption in captions) {
            captionsArray.put(caption.toJson())
        }
        put("captions", captionsArray)
    }
}

fun VideoEditState.Companion.fromJson(json: JSONObject): VideoEditState {
    val captionsArray = json.optJSONArray("captions") ?: JSONArray()
    val captionsList = mutableListOf<VideoCaption>()
    for (i in 0 until captionsArray.length()) {
        captionsList.add(VideoCaption.fromJson(captionsArray.getJSONObject(i)))
    }

    val filterName = json.optString("selectedFilter", "")
    val selectedFilter = if (filterName.isEmpty()) null else {
        try { VideoFilter.valueOf(filterName) } catch (e: Exception) { null }
    }

    return VideoEditState(
        draftId = json.getString("draftId"),
        videoUri = Uri.parse(json.getString("videoUri")),
        videoDuration = json.getDouble("videoDuration"),
        videoWidth = json.optInt("videoWidth", 1080),
        videoHeight = json.optInt("videoHeight", 1920),
        trimStartTime = json.optDouble("trimStartTime", 0.0),
        trimEndTime = json.optDouble("trimEndTime", json.getDouble("videoDuration")),
        selectedFilter = selectedFilter,
        filterIntensity = json.optDouble("filterIntensity", 1.0),
        captions = captionsList,
        isProcessing = json.optBoolean("isProcessing", false),
        processingProgress = json.optDouble("processingProgress", 0.0),
        isProcessingComplete = json.optBoolean("isProcessingComplete", false),
        processedVideoUri = json.optString("processedVideoUri", "").takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
        processedThumbnailUri = json.optString("processedThumbnailUri", "").takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
        lastModified = Date(json.optLong("lastModified", System.currentTimeMillis()))
    )
}

fun VideoCaption.toJson(): JSONObject {
    return JSONObject().apply {
        put("id", id)
        put("text", text)
        put("startTime", startTime)
        put("duration", duration)
        put("position", position.name)
        put("style", style.name)
    }
}

fun VideoCaption.Companion.fromJson(json: JSONObject): VideoCaption {
    return VideoCaption(
        id = json.getString("id"),
        text = json.getString("text"),
        startTime = json.getDouble("startTime"),
        duration = json.getDouble("duration"),
        position = try {
            CaptionPosition.valueOf(json.getString("position"))
        } catch (e: Exception) {
            CaptionPosition.BOTTOM
        },
        style = try {
            CaptionStyle.valueOf(json.getString("style"))
        } catch (e: Exception) {
            CaptionStyle.STANDARD
        }
    )
}