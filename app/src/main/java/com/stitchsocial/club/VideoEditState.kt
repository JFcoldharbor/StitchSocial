/*
 * VideoEditState.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 3: Data Types - Video Edit State Management
 * Dependencies: None (Pure Kotlin data classes)
 * Features: Trim, filter, caption state management
 *
 * Exact translation from iOS VideoEditState
 */

package com.stitchsocial.club

import android.net.Uri
import java.util.Date
import java.util.UUID

// MARK: - Video Edit State

/**
 * Complete state for video editing session
 * Matches iOS VideoEditState struct
 */
data class VideoEditState(
    val videoUri: Uri,
    val videoDuration: Double = 0.0,
    val videoWidth: Int = 1080,
    val videoHeight: Int = 1920,
    val draftId: String = UUID.randomUUID().toString(),

    // Trim state
    var trimStartTime: Double = 0.0,
    var trimEndTime: Double = 0.0,

    // Filter state
    var selectedFilter: VideoFilter? = null,
    var filterIntensity: Double = 1.0,

    // Caption state
    val captions: MutableList<VideoCaption> = mutableListOf(),

    // Processing state
    var isProcessing: Boolean = false,
    var processingProgress: Double = 0.0,
    var isProcessingComplete: Boolean = false,
    var processedVideoUri: Uri? = null,
    var processedThumbnailUri: Uri? = null,

    // Metadata
    var lastModified: Date = Date(),
    var createdAt: Date = Date()
) {
    // MARK: - Computed Properties

    val trimmedDuration: Double
        get() = trimEndTime - trimStartTime

    val hasEdits: Boolean
        get() = hasTrimEdits || hasFilterEdits || hasCaptionEdits

    val hasTrimEdits: Boolean
        get() = trimStartTime > 0.1 || (videoDuration > 0 && trimEndTime < videoDuration - 0.1)

    val hasFilterEdits: Boolean
        get() = selectedFilter != null && selectedFilter != VideoFilter.NONE

    val hasCaptionEdits: Boolean
        get() = captions.isNotEmpty()

    val videoSize: VideoSize
        get() = VideoSize(videoWidth.toFloat(), videoHeight.toFloat())

    val aspectRatio: Float
        get() = if (videoHeight > 0) videoWidth.toFloat() / videoHeight.toFloat() else 9f / 16f

    val isLandscape: Boolean
        get() = aspectRatio > 1.0f

    // MARK: - State Update Methods

    fun updateTrimRange(start: Double, end: Double) {
        trimStartTime = maxOf(0.0, start)
        trimEndTime = minOf(videoDuration, end)
        lastModified = Date()
    }

    fun setFilter(filter: VideoFilter?, intensity: Double = 1.0) {
        selectedFilter = filter
        filterIntensity = intensity.coerceIn(0.0, 1.0)
        lastModified = Date()
    }

    fun addCaption(caption: VideoCaption) {
        captions.add(caption)
        lastModified = Date()
    }

    fun removeCaption(id: String) {
        captions.removeAll { it.id == id }
        lastModified = Date()
    }

    fun updateCaption(id: String, update: (VideoCaption) -> VideoCaption) {
        val index = captions.indexOfFirst { it.id == id }
        if (index >= 0) {
            captions[index] = update(captions[index])
            lastModified = Date()
        }
    }

    fun startProcessing() {
        isProcessing = true
        processingProgress = 0.0
    }

    fun updateProcessingProgress(progress: Double) {
        processingProgress = progress.coerceIn(0.0, 1.0)
    }

    fun finishProcessing(videoUri: Uri, thumbnailUri: Uri) {
        isProcessing = false
        processingProgress = 1.0
        isProcessingComplete = true
        processedVideoUri = videoUri
        processedThumbnailUri = thumbnailUri
        lastModified = Date()
    }

    companion object {
        fun create(videoUri: Uri, duration: Double = 0.0, width: Int = 1080, height: Int = 1920): VideoEditState {
            return VideoEditState(
                videoUri = videoUri,
                videoDuration = duration,
                videoWidth = width,
                videoHeight = height,
                trimEndTime = duration
            )
        }
    }
}

// MARK: - Video Size

data class VideoSize(
    val width: Float,
    val height: Float
) {
    val aspectRatio: Float
        get() = if (height > 0) width / height else 1f
}

// MARK: - Video Filter

enum class VideoFilter(
    val displayName: String,
    val thumbnailIcon: String,
    val ciFilterName: String?
) {
    NONE("None", "close", null),
    VIVID("Vivid", "auto_awesome", "CIColorControls"),
    WARM("Warm", "wb_sunny", "CITemperatureAndTint"),
    COOL("Cool", "ac_unit", "CITemperatureAndTint"),
    DRAMATIC("Dramatic", "contrast", "CIColorControls"),
    VINTAGE("Vintage", "vintage", "CISepiaTone"),
    MONOCHROME("B&W", "monochrome_photos", "CIPhotoEffectMono"),
    CINEMATIC("Cinematic", "movie", "CIVignette"),
    SUNSET("Sunset", "wb_twilight", "CIColorMatrix");

    companion object {
        val allCases: List<VideoFilter> = values().toList()
    }
}

// MARK: - Video Caption

data class VideoCaption(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val startTime: Double,
    val duration: Double = 3.0,
    val position: CaptionPosition = CaptionPosition.CENTER,
    val style: CaptionStyle = CaptionStyle.STANDARD
) {
    val endTime: Double
        get() = startTime + duration

    companion object
}

enum class CaptionPosition(val displayName: String) {
    TOP("Top"),
    CENTER("Center"),
    BOTTOM("Bottom");

    companion object {
        val allCases: List<CaptionPosition> = values().toList()
    }
}

enum class CaptionStyle(val displayName: String) {
    STANDARD("Standard"),
    BOLD("Bold"),
    OUTLINE("Outline"),
    SHADOW("Shadow"),
    GLOW("Glow");

    companion object {
        val allCases: List<CaptionStyle> = values().toList()
    }
}

// MARK: - Edit Tab

enum class EditTab(val title: String, val icon: String) {
    TRIM("Trim", "content_cut"),
    FILTERS("Filters", "filter"),
    CAPTIONS("Captions", "subtitles");

    companion object {
        val allCases: List<EditTab> = values().toList()
    }
}