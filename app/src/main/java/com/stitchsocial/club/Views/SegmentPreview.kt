/*
 * CollectionRowViewModel.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 5: ViewModel — Collection card/row display logic
 * Mirror of Swift CollectionRowViewModel exactly.
 * Dependencies: CollectionService, VideoCollection, CoreVideoMetadata
 *
 * CACHING:
 *   - loadSegmentPreviews() delegates to CollectionService.getCollectionSegments()
 *     which is TTL-cached (10 min) in CollectionService.segmentCache.
 *     Re-opening a card = 0 Firestore reads if cache is warm.
 *   - Guard: segmentPreviews.isEmpty && !isLoadingPreviews prevents duplicate loads.
 *   - refresh() hits CollectionService.getUserCollections cache (invalidated on write).
 *
 * BATCHING:
 *   - Single getCollectionSegments call loads all segments; we take first 4 in-memory.
 *   - No per-segment reads.
 *
 * ADD TO OptimizationConfig:
 *   CollectionRow.maxPreviewCount = 4
 */

package com.stitchsocial.club.Views

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stitchsocial.club.foundation.CollectionStatus
import com.stitchsocial.club.foundation.VideoCollection
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.services.CollectionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.TimeUnit
import androidx.compose.ui.graphics.Color
import com.stitchsocial.club.BuildConfig

// ─────────────────────────────────────────────
// MARK: - SegmentPreview model
// ─────────────────────────────────────────────

/** Lightweight model for segment preview strip. Mirrors Swift SegmentPreview. */
data class SegmentPreview(
    val id: String,
    val segmentNumber: Int,
    val thumbnailURL: String?,
    val duration: Double?          // seconds
) {
    val formattedDuration: String?
        get() {
            val d = duration ?: return null
            val m = d.toInt() / 60
            val s = d.toInt() % 60
            return "%d:%02d".format(m, s)
        }

    val partLabel: String get() = "Part $segmentNumber"
}

// ─────────────────────────────────────────────
// MARK: - CollectionRowViewModel
// ─────────────────────────────────────────────

class CollectionRowViewModel(
    initialCollection: VideoCollection,
    private val collectionService: CollectionService = CollectionService()
) : ViewModel() {

    val id: String = initialCollection.id

    // ── State ────────────────────────────────────────────────────────────

    private val _collection = MutableStateFlow(initialCollection)
    val collection: StateFlow<VideoCollection> = _collection.asStateFlow()

    private val _segmentPreviews = MutableStateFlow<List<SegmentPreview>>(emptyList())
    val segmentPreviews: StateFlow<List<SegmentPreview>> = _segmentPreviews.asStateFlow()

    private val _isLoadingPreviews = MutableStateFlow(false)
    val isLoadingPreviews: StateFlow<Boolean> = _isLoadingPreviews.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isBookmarked = MutableStateFlow(false)
    val isBookmarked: StateFlow<Boolean> = _isBookmarked.asStateFlow()

    private val maxPreviewCount = 4

    // ── Display properties ───────────────────────────────────────────────

    val title: String get() = _collection.value.title

    val creatorDisplayName: String get() = "@${_collection.value.creatorName}"

    val coverImageURL: String?
        get() = _collection.value.coverImageURL
            ?: _segmentPreviews.value.firstOrNull()?.thumbnailURL

    val segmentCountText: String
        get() {
            val c = _collection.value.segmentCount
            return if (c == 1) "1 part" else "$c parts"
        }

    val durationText: String
        get() {
            val total = _collection.value.totalDuration.toInt()
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s)
            else "%d:%02d".format(m, s)
        }

    val summaryText: String get() = "$segmentCountText • $durationText"

    val viewCountText: String get() = "${formatCount(_collection.value.totalViews)} views"

    val hypeCountText: String get() = formatCount(_collection.value.totalHypes)

    val coolCountText: String get() = formatCount(_collection.value.totalCools)

    val netScore: Int get() = _collection.value.totalHypes - _collection.value.totalCools

    val netScoreText: String
        get() = when {
            netScore > 0 -> "+${formatCount(netScore)}"
            netScore < 0 -> formatCount(netScore)
            else -> "0"
        }

    val netScoreColor: Color
        get() = when {
            netScore > 0 -> Color(0xFF30D158)
            netScore < 0 -> Color(0xFFFF453A)
            else -> Color.Gray
        }

    val replyCountText: String
        get() {
            val c = _collection.value.totalReplies
            return if (c == 1) "1 reply" else "${formatCount(c)} replies"
        }

    val timeAgoText: String
        get() = _collection.value.publishedAt?.let { formatTimeAgo(it) } ?: "Draft"

    val isPublished: Boolean get() = _collection.value.status == CollectionStatus.PUBLISHED

    val isDraft: Boolean get() = _collection.value.status == CollectionStatus.DRAFT

    val statusBadgeText: String?
        get() = when (_collection.value.status) {
            CollectionStatus.DRAFT -> "Draft"
            CollectionStatus.PROCESSING -> "Processing"
            CollectionStatus.ARCHIVED -> "Archived"
            CollectionStatus.PUBLISHED, CollectionStatus.DELETED -> null
        }

    val statusBadgeColor: Color
        get() = when (_collection.value.status) {
            CollectionStatus.DRAFT -> Color(0xFFFF9F0A)
            CollectionStatus.PROCESSING -> Color(0xFF0A84FF)
            CollectionStatus.ARCHIVED -> Color.Gray
            CollectionStatus.PUBLISHED -> Color(0xFF30D158)
            CollectionStatus.DELETED -> Color(0xFFFF453A)
        }

    val additionalSegmentCount: Int
        get() = maxOf(0, _collection.value.segmentCount - maxPreviewCount)

    val additionalSegmentsText: String?
        get() = if (additionalSegmentCount > 0) "+$additionalSegmentCount" else null

    val showMoreSegmentsIndicator: Boolean get() = additionalSegmentCount > 0

    // ── Load segment previews ─────────────────────────────────────────────

    /**
     * Loads up to 4 segment thumbnails for the preview strip.
     * COST: served from CollectionService.segmentCache (10-min TTL) — typically 0 reads.
     * Guard prevents duplicate loads.
     */
    fun loadSegmentPreviews() {
        val previews = _segmentPreviews.value
        if (previews.isNotEmpty() || _isLoadingPreviews.value) return

        _isLoadingPreviews.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val segments = collectionService.getCollectionSegments(_collection.value.id)
                val previewSegs = segments.take(maxPreviewCount)

                val result = if (previewSegs.isNotEmpty()) {
                    previewSegs.mapIndexed { index, seg ->
                        SegmentPreview(
                            id = seg.id,
                            segmentNumber = index + 1,
                            thumbnailURL = seg.thumbnailURL.takeIf { it.isNotEmpty() },
                            duration = seg.duration
                        )
                    }
                } else {
                    // Placeholder from segmentIDs if segments not yet loaded
                    _collection.value.segmentIDs.take(maxPreviewCount).mapIndexed { index, segID ->
                        SegmentPreview(id = segID, segmentNumber = index + 1, thumbnailURL = null, duration = null)
                    }
                }

                _segmentPreviews.value = result
                if (BuildConfig.DEBUG) { println("📚 COLLECTION ROW VM: Loaded ${result.size} previews for ${_collection.value.id.take(8)}") }
            } catch (e: Exception) {
                _error.value = "Failed to load previews"
                if (BuildConfig.DEBUG) { println("❌ COLLECTION ROW VM: ${e.message}") }
            } finally {
                _isLoadingPreviews.value = false
            }
        }
    }

    fun toggleBookmark() {
        _isBookmarked.value = !_isBookmarked.value
        if (BuildConfig.DEBUG) { println("📚 COLLECTION ROW VM: Bookmark → ${_isBookmarked.value} for ${_collection.value.id}") }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val updated = collectionService.getCollection(_collection.value.id)
                if (updated != null) _collection.value = updated
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) { println("❌ COLLECTION ROW VM: Refresh failed: ${e.message}") }
            }
        }
    }

    // ── Formatting helpers ────────────────────────────────────────────────

    private fun formatCount(count: Int): String = when {
        count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
        count >= 1_000 -> "%.1fK".format(count / 1_000.0)
        else -> "$count"
    }

    private fun formatTimeAgo(date: Date): String {
        val diff = System.currentTimeMillis() - date.time
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff).toInt()
        val hours = TimeUnit.MILLISECONDS.toHours(diff).toInt()
        val days = TimeUnit.MILLISECONDS.toDays(diff).toInt()
        val weeks = days / 7
        val months = days / 30
        val years = days / 365

        return when {
            years > 0 -> if (years == 1) "1 year ago" else "$years years ago"
            months > 0 -> if (months == 1) "1 month ago" else "$months months ago"
            weeks > 0 -> if (weeks == 1) "1 week ago" else "$weeks weeks ago"
            days > 0 -> if (days == 1) "1 day ago" else "$days days ago"
            hours > 0 -> if (hours == 1) "1 hour ago" else "$hours hours ago"
            minutes > 0 -> if (minutes == 1) "1 min ago" else "$minutes mins ago"
            else -> "Just now"
        }
    }

    // ── Equatable / Hashable ──────────────────────────────────────────────

    override fun equals(other: Any?): Boolean =
        other is CollectionRowViewModel && other.id == id

    override fun hashCode(): Int = id.hashCode()
}

// ─────────────────────────────────────────────
// MARK: - ViewModel Factory
// ─────────────────────────────────────────────

class CollectionRowViewModelFactory(
    private val collection: VideoCollection,
    private val collectionService: CollectionService = CollectionService()
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return CollectionRowViewModel(collection, collectionService) as T
    }
}