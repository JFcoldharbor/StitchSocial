/*
 * CollectionPlayerViewModel.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 5: ViewModel — Collection playback state
 * Mirror of Swift CollectionPlayerView + CollectionRowViewModel logic
 * Dependencies: CollectionService, VideoCollection, CollectionProgress, CoreVideoMetadata
 *
 * CACHING: Delegates entirely to CollectionService (segments cached 10 min,
 *          progress cached in-memory). VM holds no independent cache.
 * BATCHING: Single getCollectionSegments call loads all segments up-front.
 *           No per-segment Firestore reads during playback.
 *
 * ADD TO CachingOptimization:
 *   CollectionPlayerViewModel.segments — loaded once per collectionID, served from
 *   CollectionService.segmentCache (10-min TTL). Progress auto-saved every 10s via
 *   saveProgressTimer and on segment advance.
 */

package com.stitchsocial.club.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stitchsocial.club.foundation.CollectionProgress
import com.stitchsocial.club.foundation.CoreVideoMetadata
import com.stitchsocial.club.foundation.VideoCollection
import com.stitchsocial.club.services.CollectionService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CollectionPlayerViewModel(
    private val collectionService: CollectionService = CollectionService()
) : ViewModel() {

    // ─────────────────────────────────────────────
    // MARK: - State
    // ─────────────────────────────────────────────

    private val _collection = MutableStateFlow<VideoCollection?>(null)
    val collection: StateFlow<VideoCollection?> = _collection.asStateFlow()

    private val _segments = MutableStateFlow<List<CoreVideoMetadata>>(emptyList())
    val segments: StateFlow<List<CoreVideoMetadata>> = _segments.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _progress = MutableStateFlow<CollectionProgress?>(null)
    val progress: StateFlow<CollectionProgress?> = _progress.asStateFlow()

    /** Elapsed seconds in current segment — updated by player callback */
    private var currentTimestamp: Double = 0.0

    private var currentUserID: String = ""
    private var progressTimerJob: Job? = null

    // ─────────────────────────────────────────────
    // MARK: - Load
    // ─────────────────────────────────────────────

    /**
     * Load a collection and its segments.
     * Resumes from saved progress if available.
     * @param startAtIndex override saved progress position (e.g. user taps specific segment)
     */
    fun loadCollection(collection: VideoCollection, userID: String, startAtIndex: Int? = null) {
        currentUserID = userID
        _collection.value = collection
        _error.value = null

        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Load segments (cached 10 min)
                val segs = collectionService.getCollectionSegments(collection.id)
                _segments.value = segs

                // Load saved progress
                val saved = collectionService.getWatchProgress(userID, collection.id)
                _progress.value = saved

                // Determine starting index
                val startIdx = startAtIndex
                    ?: saved?.currentSegmentIndex?.coerceIn(0, (segs.size - 1).coerceAtLeast(0))
                    ?: 0
                _currentIndex.value = startIdx

                println("▶️ COLLECTION PLAYER: Loaded ${segs.size} segments, starting at $startIdx")
                startProgressTimer()

            } catch (e: Exception) {
                _error.value = "Failed to load collection: ${e.message}"
                println("❌ COLLECTION PLAYER: Load failed: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ─────────────────────────────────────────────
    // MARK: - Playback Control
    // ─────────────────────────────────────────────

    val currentSegment: CoreVideoMetadata?
        get() = _segments.value.getOrNull(_currentIndex.value)

    val totalSegments: Int get() = _segments.value.size
    val hasNext: Boolean get() = _currentIndex.value < _segments.value.size - 1
    val hasPrevious: Boolean get() = _currentIndex.value > 0

    /** Called by ExoPlayer listener every second */
    fun onPlaybackProgress(positionSeconds: Double) {
        currentTimestamp = positionSeconds
    }

    /** Advance to next segment. Marks current as complete, saves progress. */
    fun advanceToNext() {
        val segs = _segments.value
        val idx = _currentIndex.value
        if (idx >= segs.size - 1) return

        val currentSeg = segs.getOrNull(idx) ?: return
        val nextSeg = segs[idx + 1]

        val updated = (_progress.value
            ?: CollectionProgress.start(currentUserID, _collection.value?.id ?: "", currentSeg.id))
            .withSegmentCompleted(currentSeg.id)
            .withAdvanceToNextSegment(nextSeg.id, idx + 1)
            .withPercentUpdated(segs.size)

        _progress.value = updated
        _currentIndex.value = idx + 1
        currentTimestamp = 0.0

        saveProgress(updated)
        println("⏭️ COLLECTION PLAYER: Advanced to segment ${idx + 1}/${segs.size}")
    }

    /** Go back to previous segment. */
    fun advanceToPrevious() {
        val idx = _currentIndex.value
        if (idx <= 0) return
        _currentIndex.value = idx - 1
        currentTimestamp = 0.0
        println("⏮️ COLLECTION PLAYER: Back to segment ${idx - 1}")
    }

    /** Jump to a specific segment index (user taps segment list). */
    fun seekToSegment(index: Int) {
        val segs = _segments.value
        if (index !in segs.indices) return
        saveCurrentPositionToProgress()
        _currentIndex.value = index
        currentTimestamp = 0.0
        println("⏩ COLLECTION PLAYER: Jumped to segment $index")
    }

    // ─────────────────────────────────────────────
    // MARK: - Progress Persistence
    // ─────────────────────────────────────────────

    /** Auto-save timer — saves every 10 seconds during playback */
    private fun startProgressTimer() {
        progressTimerJob?.cancel()
        progressTimerJob = viewModelScope.launch {
            while (true) {
                delay(10_000)
                saveCurrentPositionToProgress()
            }
        }
    }

    private fun saveCurrentPositionToProgress() {
        val segs = _segments.value
        val idx = _currentIndex.value
        val seg = segs.getOrNull(idx) ?: return
        val collID = _collection.value?.id ?: return

        val base = _progress.value
            ?: CollectionProgress.start(currentUserID, collID, seg.id)

        val updated = base.withUpdatedPosition(seg.id, idx, currentTimestamp)
        _progress.value = updated
        saveProgress(updated)
    }

    private fun saveProgress(progress: CollectionProgress) {
        viewModelScope.launch {
            try {
                collectionService.updateWatchProgress(progress)
            } catch (e: Exception) {
                println("⚠️ COLLECTION PLAYER: Progress save failed: ${e.message}")
            }
        }
    }

    // ─────────────────────────────────────────────
    // MARK: - Engagement
    // ─────────────────────────────────────────────

    /** Record a view on the collection when first segment starts. */
    fun recordView() {
        val collID = _collection.value?.id ?: return
        viewModelScope.launch {
            collectionService.incrementCollectionField(collID, "totalViews", 1)
        }
    }

    // ─────────────────────────────────────────────
    // MARK: - Cleanup
    // ─────────────────────────────────────────────

    override fun onCleared() {
        progressTimerJob?.cancel()
        saveCurrentPositionToProgress()
        super.onCleared()
        println("🧹 COLLECTION PLAYER: ViewModel cleared, progress saved")
    }
}